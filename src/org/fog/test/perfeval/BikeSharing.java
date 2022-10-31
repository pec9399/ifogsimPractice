package org.fog.test.perfeval;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.PhysicalTopology;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.JsonToTopology;
import org.fog.utils.Logger;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;
import org.cloudbus.cloudsim.Log;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.*;
public class BikeSharing{
	
	static List<FogDevice> fogDevices = new ArrayList<FogDevice>();
	static List<Sensor> sensors = new ArrayList<Sensor>();
	static List<Actuator> actuators = new ArrayList<Actuator>();
	static int numOfAreas = 2;
	static int numOfBikesPerArea = 2;
	
	private static boolean CLOUD = true;
	
	
	@SuppressWarnings({"serial" })
	private static Application createApplication(String appId, int userId){
		
		Application application = Application.createApplication(appId, userId);
		
		application.addAppModule("location_tracker", 100,10,10000);
		application.addAppModule("location_processor", 1000,100,20000);
		application.addAppModule("user_database", 1000,1000,100000);
		
		
		application.addAppEdge("GPS", "location_tracker", 1000, 20000, "GPS", Tuple.UP, AppEdge.SENSOR);
		application.addAppEdge("location_tracker", "location_processor", 1000, 20000, "GPS_DATA_STREAM", Tuple.UP, AppEdge.MODULE);
		application.addAppEdge("location_processor", "user_database", 20000, 2000, "LOCATION", Tuple.UP, AppEdge.MODULE);
		
		application.addTupleMapping("location_tracker", "GPS", "GPS_DATA_STREAM", new FractionalSelectivity(1.0));
		application.addTupleMapping("location_processor", "GPS_DATA_STREAM", "LOCATION", new FractionalSelectivity(1.0));
		
		final AppLoop loop1 = new AppLoop(new ArrayList<String>(){{add("location_tracker");add("location_processor");add("user_database");}});
		List<AppLoop> loops = new ArrayList<AppLoop>(){{add(loop1);}};
		
		application.setLoops(loops);
		return application;
	}
	
	/**
	 * Creates a vanilla fog device
	 * @param nodeName name of the device to be used in simulation
	 * @param mips MIPS
	 * @param ram RAM
	 * @param upBw uplink bandwidth
	 * @param downBw downlink bandwidth
	 * @param level hierarchy level of the device
	 * @param ratePerMips cost rate per MIPS used
	 * @param busyPower
	 * @param idlePower
	 * @return
	 */
	private static FogDevice createFogDevice(String nodeName, long mips,
			int ram, long upBw, long downBw, int level, double ratePerMips, double busyPower, double idlePower) {
		
		List<Pe> peList = new ArrayList<Pe>();
		// 3. Create PEs and add these into a list.
		peList.add(new Pe(0, new PeProvisionerOverbooking(mips))); // need to store Pe id and MIPS Rating
		int hostId = FogUtils.generateEntityId();
		long storage = 1000000; // host storage
		int bw = 10000;
		PowerHost host = new PowerHost(
				hostId,
				new RamProvisionerSimple(ram),
				new BwProvisionerOverbooking(bw),
				storage,
				peList,
				new StreamOperatorScheduler(peList),
				new FogLinearPowerModel(busyPower, idlePower)
			);
		List<Host> hostList = new ArrayList<Host>();
		hostList.add(host);
		String arch = "x86"; // system architecture
		String os = "Linux"; // operating system
		String vmm = "Xen";
		double time_zone = 10.0; // time zone this resource located
		double cost = 3.0; // the cost of using processing in this resource
		double costPerMem = 0.05; // the cost of using memory in this resource
		double costPerStorage = 0.001; // the cost of using storage in this
										// resource
		double costPerBw = 0.0; // the cost of using bw in this resource
		LinkedList<Storage> storageList = new LinkedList<Storage>(); // we are not adding SAN
													// devices by now
		FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
				arch, os, vmm, host, time_zone, cost, costPerMem,
				costPerStorage, costPerBw);
		FogDevice fogdevice = null;
		try {
			fogdevice = new FogDevice(nodeName, characteristics,
					new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, ratePerMips);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		fogdevice.setLevel(level);
		return fogdevice;
	}
	
	/**
	 * Creates the fog devices in the physical topology of the simulation.
	 * @param userId
	 * @param appId
	 */
	private static void createFogDevices(int userId, String appId) {
		FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16*103, 16*83.25);
		cloud.setParentId(-1);
		fogDevices.add(cloud);
		FogDevice djs = createFogDevice("daejeon-server", 28000, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
		djs.setParentId(cloud.getId());
		djs.setUplinkLatency(100); // latency of connection between edge server and cloud is 100 ms
		fogDevices.add(djs);
		FogDevice bss = createFogDevice("busan-server", 28000, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
		bss.setParentId(cloud.getId());
		bss.setUplinkLatency(100);
		fogDevices.add(bss);
		
		addArea("0", userId, appId, djs.getId());
		addArea("1", userId, appId, bss.getId());
		
	}
	
	private static FogDevice addBike(String id, int userId, String appId, int parentId){
		FogDevice bike = createFogDevice("Bike-"+id, 200, 1000, 1000, 1000, 2, 0, 0,0);
		bike.setParentId(parentId);
		Sensor sensor = new Sensor("gps-"+id, "GPS", userId, appId, new DeterministicDistribution(5)); // inter-transmission time of camera (sensor) follows a deterministic distribution
		sensors.add(sensor);
		sensor.setGatewayDeviceId(bike.getId());
		sensor.setLatency(1.0); // latency of connection between bike and gps sensor (consider gps time)
		return bike;
	}
	private static void addArea(String id, int userId, String appId, int parentId){
		for(int i=0;i<numOfBikesPerArea;i++){
			String mobileId = id+"-"+i;
			FogDevice bike = addBike(mobileId, userId, appId, parentId); // adding a smart camera to the physical topology. Smart cameras have been modeled as fog devices as well.
			bike.setUplinkLatency(50); //latency between bike and proxy server
			fogDevices.add(bike);
			
		}
	}
	
	public static void main(String args[]) {
		Log.printLine("Starting bike sharing simulation...");
		try {
		Log.disable();
		Logger.ENABLED=true;
		int num_user = 1; // number of cloud users
		Calendar calendar = Calendar.getInstance();
		boolean trace_flag = false; // mean trace events
		CloudSim.init(num_user, calendar, trace_flag);
		
		String appId = "BikeSharingApplication";
		FogBroker broker = new FogBroker("broker");
		Application application = createApplication(appId, broker.getId());
		application.setUserId(broker.getId());
		createFogDevices(broker.getId(), appId);
		//PhysicalTopology physicalTopology = JsonToTopology.getPhysicalTopology(broker.getId(), appId, "topologies/bikesharing");
		//fogDevices = physicalTopology.getFogDevices();
		//sensors = physicalTopology.getSensors();
		//actuators = physicalTopology.getActuators();
	
		Controller controller = null;
		
		ModuleMapping moduleMapping = ModuleMapping.createModuleMapping(); // initializing a module mapping
		for(FogDevice device : fogDevices){
			if(device.getName().startsWith("Bike")){ // names of all Smart Cameras start with ‘m’
				moduleMapping.addModuleToDevice("location_tracker", device.getName()); // fixing 1 instance of the Motion Detector module to each Smart Camera
			}
		}
		moduleMapping.addModuleToDevice("user_database", "cloud"); // fixing instances of User Interface module in the Cloud
		if(CLOUD){
			// if the mode of deployment is cloud-based
			moduleMapping.addModuleToDevice("location_processor", "cloud"); // placing all instances of Object Detector module in the Cloud
		}
		
		controller = new Controller("master-controller", fogDevices, sensors,
				actuators);
		
		controller.submitApplication(application,
				(CLOUD)?(new ModulePlacementMapping(fogDevices, application, moduleMapping))
						:(new ModulePlacementEdgewards(fogDevices, sensors, actuators, application, moduleMapping)));
		
		TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
		
		CloudSim.startSimulation();
		CloudSim.stopSimulation();
		Log.printLine("Bike sharing simulation finished!");
		} catch(Exception e) {
			e.printStackTrace();
			Log.printLine("Unexpected error has occured.");
		}
	}
	
}