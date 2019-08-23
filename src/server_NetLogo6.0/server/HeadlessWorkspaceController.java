//Customized for NL4Py by Chathika Gunaratne <chathikagunaratne@gmail.com>
package nl4py.server;
import py4j.GatewayServer;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import bsearch.nlogolink.NetLogoLinkException;
import javax.imageio.ImageIO;
import bsearch.space.*;
import java.util.HashMap;
import org.nlogo.headless.HeadlessWorkspace; 
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Phaser;

public class HeadlessWorkspaceController extends NetLogoController {
	
	HeadlessWorkspace ws;
	private ArrayBlockingQueue<String> commandQueue;
	private Thread commandThread;
	boolean controllerNeeded = false;
	LinkedBlockingQueue<String> scheduledReporterResults = new LinkedBlockingQueue<String>();
	volatile String mon = new String("wait");
	volatile boolean scheduleDone = true;	
	private Phaser ph;

	public HeadlessWorkspaceController(Phaser ph) {
		//Create new workspace instance
		ws = HeadlessWorkspace.newInstance();
		this.ph = ph;
		commandQueue = new ArrayBlockingQueue<String>(100);
		commandThread = new Thread(new Runnable() {
			/**
			* Checks if the queue has been poisoned with the thread stop condition
			* If yes, interrupt and free resources.
			* If no, return the intended command String
			* Blocks on the commandQueue
			*/			
			private String safelyGetNextCommand() throws InterruptedException{
				String nextCommand = commandQueue.take();
				if(nextCommand.equalsIgnoreCase("~stop~")) {
					nextCommand = "";
					controllerNeeded = false;
					Thread.currentThread().interrupt();
				}
				return nextCommand;
			}
			@Override
			public void run() {
				//System.out.println("command thread started");
				controllerNeeded = true;
				while (controllerNeeded || !Thread.currentThread().interrupted()) {
					//get next command out of queue
					try{
						//System.out.println("taking next command");
						String nextCommand = safelyGetNextCommand();
						if(nextCommand.equalsIgnoreCase("~ScheduledReporters~")){
							scheduleDone = false;
							//Read in the schedule
							ArrayList<String> reporters = new ArrayList<String>();
							nextCommand = safelyGetNextCommand();
							while (!nextCommand.equalsIgnoreCase("~StartAt~")) {
								reporters.add(nextCommand);
								nextCommand = safelyGetNextCommand();
								if (nextCommand == null ) {nextCommand = "";}
							} 
							int startAtTick = Integer.parseInt(safelyGetNextCommand());
							nextCommand = safelyGetNextCommand();
							int intervalTicks = Integer.parseInt(safelyGetNextCommand());
							nextCommand = safelyGetNextCommand();
							int stopAtTick = Integer.parseInt(safelyGetNextCommand());
							nextCommand = safelyGetNextCommand();
							boolean isBlocking = Boolean.parseBoolean(safelyGetNextCommand());
							nextCommand = safelyGetNextCommand();
							String goCommand = safelyGetNextCommand();							
							//If the schedule and run was blocking then tell the main thread to wait on me by registering the phaser
							if (isBlocking) {
								ph.register();
							}							
							//Now execute the schedule
							//Has start time passed?
							int ticksOnModel = ((Double)ws.report("ticks")).intValue();
							if(ticksOnModel <= startAtTick ){
								boolean modelStopped = false;
								if(ticksOnModel < startAtTick) {
									//catch up if necessary
									ws.command("repeat " + Double.toString(startAtTick - ticksOnModel) +" [go]");
								}
								String commandString = "let nl4pyData (list) repeat " + Integer.toString(stopAtTick - startAtTick) +" [ " + goCommand + " let resultsThisTick (list " ; 
								for(String reporter : reporters) {
									commandString = commandString + "(" +reporter + ") ";
								}
								commandString = commandString + ") set nl4pyData lput resultsThisTick nl4pyData ] ask patch 0 0 [set plabel nl4pyData]";
								ws.command(commandString);								
								scala.collection.Iterator resultsIterator = ((org.nlogo.core.LogoList)ws.report("[plabel] of patch 0 0")).toIterator();
								synchronized(scheduledReporterResults){	
									while(resultsIterator.hasNext()) {
										org.nlogo.core.LogoList resultsThisTick = (org.nlogo.core.LogoList)resultsIterator.next();
										scala.collection.Iterator resultsThisTickIterator = resultsThisTick.toIterator();
										while(resultsThisTickIterator.hasNext()){
											scheduledReporterResults.put(resultsThisTickIterator.next().toString());
										}										
									}
								}
								ws.command("ask patch 0 0 [set plabel 0]");
							}
							scheduleDone = true;
							if (isBlocking) {
								ph.arriveAndDeregister();
							}
						} else {
							//System.out.println("sending next command");
							ws.command(nextCommand);
							//System.out.println("command done");
						}	
					} catch (InterruptedException e){
						//System.out.println("Shutting down command thread" + Thread.currentThread().getName());
						controllerNeeded = false;
						Thread.currentThread().interrupt();
						break;
					} catch (NullPointerException e){
						if (ws == null) {
							break;
						}
					} finally {scheduleDone = true;}
				}
			}
		});
		commandThread.start();
	}
	
	/**
	 * Create a new workspace
	 * Load a NetLogo model file into the headless workspace
	 * @param path: Path to the .nlogo file to load.
	 */
	public void openModel(String path) {
		
		//System.out.println("opening" + path);
		try {
			ws.open(path);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		} 		
	}
	
	/**
	 * Close the Netlogo file.
	 * @param unique id for this model
	 */
	public void closeModel(){
		try {
			ws.halt();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Create a new headless instance. 
	 * Use this after closeModel() to instantiate a
	 * new instance of Netlogo. Great for multiple runs!
	 */
	public void refresh(){
		try {
			ws.dispose();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		ws = HeadlessWorkspace.newInstance();
	}
	
	/**
	 * Export a view (the visualization area) to the Java file's working directory.
	 * @param filename: Name used to save the file. Include .png (ex: file.png)
	 */
	public void exportView(String filename){
		try {
			BufferedImage img = ws.exportView();
		    File outputfile = new File(filename);
		    ImageIO.write(img, "png", outputfile);
		} catch (IOException e) {
		    e.printStackTrace();
		}		
	}
	
	/**
	 * Send a command to the open NetLogo model.
	 * @param command: NetLogo command syntax.
	 */
	public void command(String command) {
		try {
			this.scheduleCommand(command);
		} catch (Exception e) {
			e.printStackTrace();
		} 
	}
	private void scheduleCommand(String newCommand) {
		try{
			commandQueue.put(newCommand);
		} catch (InterruptedException e){
			e.printStackTrace();
		}
	}
	/**
	 * Get the value of a variable in the NetLogo model.
	 * @param command: The value to report.
	 * @return Java Object containing return info
	 */
	public Object report(String command) {
		Object report = new Double(0.0);
		try {
			//Thread.sleep(1);
			report = ws.report(command);
		} catch (Exception e) {
			// in case a run crashes due to a NetLogo side exception, return 0
			report = "NetLogo Exception";//new Double(0.0);
			return report;
			//e.printStackTrace();
		} 
		return report;
	}
	
	public void scheduleReportersAndRun (ArrayList<String> reporters, int startAtTick, int intervalTicks, int stopAtTick, String goCommand, boolean isBlocking){
		try{
			commandQueue.put("~ScheduledReporters~");
			for (String reporter : reporters) {
				commandQueue.put(reporter);
			}
			commandQueue.put("~StartAt~");
			commandQueue.put(Integer.toString(startAtTick));
			commandQueue.put("~Interval~");
			commandQueue.put(Integer.toString(intervalTicks));
			commandQueue.put("~StopAt~");
			commandQueue.put(Integer.toString(stopAtTick));
			commandQueue.put("~isBlocking~");
			commandQueue.put(Boolean.toString(isBlocking));
			commandQueue.put("~RunReporters~");
			commandQueue.put(goCommand);			
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	public ArrayList<String> getScheduledReporterResults () {
		ArrayList<String> results  = new ArrayList<String>();		
		try {	
			if(scheduleDone) {
				synchronized(scheduledReporterResults){
					scheduledReporterResults.drainTo(results);
				}
			} 
		} catch (Exception e) {
			e.printStackTrace();
		}		
		return results;
	}	
	
	public SearchSpace getParamList(String path) {
		String constraintsText = "";
		SearchSpace ss = null;
		try{
		constraintsText = bsearch.nlogolink.Utils.getDefaultConstraintsText(path);
		
		ss = new SearchSpace(java.util.Arrays.asList(constraintsText.split("\n")));
		for(ParameterSpec paramSpec : ss.getParamSpecs()) {
			//System.out.println(paramSpec.getClass());
		}
		} catch (NetLogoLinkException e)
		{
			e.printStackTrace();			
		}
		return ss;
	}
	
	protected void disposeWorkspace(){
		this.closeModel();
		
		try{	commandQueue.put("~stop~");
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (Exception e){
			e.printStackTrace();
		}
		controllerNeeded = false;
		try{
			commandThread.interrupt();
		} catch (Exception e){
			e.printStackTrace();
		}
		try{
			ws.dispose();
		} catch (Exception e){
			e.printStackTrace();
		}
		ws = null;
		System.gc();
	}
}