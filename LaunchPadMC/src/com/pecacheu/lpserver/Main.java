//This work is licensed under a GNU General Public License. Visit http://gnu.org/licenses/gpl-3.0-standalone.html for details.
//LaunchPadMC Server. Copyright (©) 2016, Pecacheu (Bryce Peterson, bbryce.com).

package com.pecacheu.lpserver;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.pecacheu.elevators.Conf;
import com.pecacheu.elevators.Elevator;
import com.pecacheu.elevators.Floor;

public class Main extends JavaPlugin {
	static final String PERM_ADMIN = "launchpadmc.admin",
	MSG_BADGE = "§e[LaunchPadMC]", PLUGIN_PATH = "plugins/LaunchPadMC/";
	
	int PORT = 25585;
	volatile ServerSocket server = null;
	volatile boolean enabled = false;
	volatile Socket client = null;
	volatile Thread sThread = null;
	volatile Thread sReadThread = null;
	volatile Thread pingThread = null;
	volatile BufferedReader in = null;
	volatile PrintWriter out = null;
	volatile int pingTime = 0;
	volatile int pingSendTime = 0;
	
	volatile int elevOffset = 0;
	volatile int elevMax = 8;
	volatile int lpmcMode = 0;
	volatile Location lpmcLoc = null;
	
	@Override
	public void onEnable() {
		new Thread(() -> {
			sleep(1000); //err("Elevators plugin could not be loaded!"); onDisable(); return;
			com.pecacheu.elevators.Main main = (com.pecacheu.elevators.Main)JavaPlugin.getPlugin(com.pecacheu.elevators.Main.class);
			if(main == null) { err("Elevators plugin not detected!"); onDisable(); return; } //Self-disable without elevators plugin.
			new File(PLUGIN_PATH).mkdirs(); loadConfig(); initThreads();
		}).start();
		
		//JavaPlugin.getPlugin(com.pecacheu.elevators.Main.class);
		//getServer().getPluginManager().registerEvents(this, this);
	}
	
	private void loadConfig() { try {
		final String CONF = PLUGIN_PATH+"config";
		if(ChuConf.confExists(CONF)) {
			ChuConf conf = ChuConf.load(CONF); Object p = conf.getProp("port");
			if(!(p instanceof Integer)) { err("Config read error: Port must be integer! Defaulting to "+PORT+"."); return; }
			PORT = ((Integer)p);
		} else saveConf(CONF); //Create new config.
	} catch (Exception e) { err("Error while loading config file",e); }}
	
	private void saveConf(String path) throws Exception {
		ChuConf conf = new ChuConf(); conf.setProp("port", PORT);
		conf.save(path);
	}
	
	private void initThreads() {
		dbg("§bStarting LaunchPadMC...");
		
		if(!startServer()) { onDisable(); return; }
		
		sThread = new Thread(() -> { while(true) try { //Open client and write data:
			while(!enabled) waitForClient();
			
			//Major Elevator Data Update:
			String str = "E"; synchronized(Conf.API_SYNC) {
			Object[] eKeys = Conf.elevators.keySet().toArray();
			int eLen = eKeys.length; if(elevOffset >= eLen) elevOffset = 0; if(eLen > elevMax) eLen = elevMax;
			for(int s=elevOffset; s<eLen; s++) { //Iterate through elevators:
				Elevator elev = Conf.elevators.get(eKeys[s]);
				
				//int fHeight = Math.round(map(elev.getLevel(), elev.yMin(), elev.yMax(), 0, 8));
				int fCount = elev.sGroups.get(0).length, moveDir = elev.floor.moving?(elev.moveDir?2:1):0;
				
				int fLevel = 0; //Get current level:
				for(int m=0,k=elev.csData.length; m<k; m++) if(Conf.ATLV.equals(elev.csData
				.get(m)) || Conf.M_ATLV.equals(elev.csData.get(m))) { fLevel = m; break; }
				if(fLevel >= fCount) fLevel = 0; //Sanity check floor number, just in case.
				
				str += "$"+eKeys[s]+"&"+fCount+"&"+moveDir+"&"+fLevel;
			}} sendMsg(str);
			
			//Conf.DISABLED = true;
			
			sleep(800);
		} catch(Exception e) { err("Server thread error: "+e.getClass().getName()); }});
		
		sReadThread = new Thread(() -> { while(true) //try { //Read data from client:
			if(enabled) { String line = readClientLine(); //if(line.length() > 0 && !line.equals("PING")) dbg("Client Msg: '"+line+"'");
			String msg = null;
			
			if(line.startsWith("Q")) { synchronized(Conf.API_SYNC) { //Elevator Data Query:
				String[] args = line.split("\\$"); Elevator elev = Conf.elevators.get(args[1]);
				if(elev != null) {
					String csStr = ""; //Get call sign data:
					for(int i=0,l=elev.csData.length; i<l; i++) { //Iterate through call sign data:
						String dat = elev.csData.get(i); int num = 0;
						if(dat == Conf.ATLV) num = 1; if(dat == Conf.M_ATLV) num = 2;
						if(dat == Conf.UP) num = 3; if(dat == Conf.C_UP) num = 4;
						if(dat == Conf.DOWN) num = 5; if(dat == Conf.C_DOWN) num = 6;
						csStr += (i==0?"":"&")+num;
					}
					//Get player count:
					Floor fl = elev.floor; int yMin = elev.yMin(), yMax = elev.yMax(), xMin =
					fl.xMin, xMax = fl.xMax, zMin = fl.zMin, zMax = fl.zMax; int pCount = 0;
					Object[] eList = fl.world.getEntitiesByClass(org.bukkit.entity.LivingEntity.class).toArray(); //Get LivingEntity list.
					for(int i=0,l=eList.length; i<l; i++) { //Iterate through entities:
						Location loc = ((Entity)eList[i]).getLocation(); double eX = loc.getX(), eY = loc.getY(), eZ = loc.getZ();
						if((eX >= xMin && eX < xMax+1) && (eY >= yMin && eY < yMax+1) && (eZ >= zMin && eZ < zMax+1)) pCount++;
					}
					msg = "Q$"+csStr+"$"+pCount+"$"+((Conf.CLTMR != null && !elev.floor.moving)?"O":"C");
				}
			}} else if(line.startsWith("G")) { synchronized(Conf.API_SYNC) { //Goto Floor Request:
				String[] args = line.split("\\$"); Elevator elev = Conf.elevators.get(args[1]);
				if(elev != null && !elev.floor.moving) {
					int ind = Integer.parseInt(args[2]); Block lSign = elev.sGroups.get(0).get(ind);
					if(lSign != null) { int fLevel = elev.getLevel(), sLevel = lSign.getY()-2;
						Conf.plugin.setTimeout(() -> {
							if(fLevel != sLevel) { //Call Elevator to Floor:
								dbg(Conf.MSG_CALL); int speed = Conf.BL_SPEED.get(Conf.BLOCKS.indexOf(elev.floor.fType.toString()));
								elev.gotoFloor(fLevel, sLevel, ind, speed);
							} else elev.doorTimer(sLevel+2); //Re-open doors if already on level.
						}, 50);
					}
				}
			}} else if(line.startsWith("D")) { synchronized(Conf.API_SYNC) { //Open/Close Elevator Doors:
				String[] args = line.split("\\$"); Elevator elev = Conf.elevators.get(args[1]);
				if(elev != null && !elev.floor.moving) {
					if(Conf.CLTMR != null) { Conf.CLTMR.cancel(); Conf.CLTMR = null; }
					if(args[2].equals("O")) Conf.plugin.setTimeout(() -> { elev.doorTimer(elev.getLevel()+2); }, 50);
					else Conf.plugin.setTimeout(() -> { elev.setDoors(elev.getLevel()+2, false); }, 50);
				}
			}} else if(line.startsWith("F")) { synchronized(Conf.API_SYNC) { //Floor Dimension Query:
				String[] args = line.split("\\$"); Elevator elev = Conf.elevators.get(args[1]);
				if(elev != null) {
					Floor fl = elev.floor;
					double cX = (fl.xMax/2.0)+(fl.xMin/2.0)+0.5, cZ = (fl.zMax/2.0)+(fl.zMin/2.0)+0.5;
					Player p = closestPlayer(fl.world, cX, cZ, 20); //Find closest player within 20 blocks.
					
					int pRel = 4; if(p != null) { //Get player's direction relative to elevator:
						double pX = p.getLocation().getX()-cX, pZ = p.getLocation().getZ()-cZ;
						boolean dirA = pX-pZ>0, dirB = pX+pZ>0;
						if(!dirA &&  dirB) pRel = 0; //North
						if(!dirA && !dirB) pRel = 1; //East
						if(dirA  && !dirB) pRel = 2; //South
						if(dirA  &&  dirB) pRel = 3; //West
					}
					
					msg = "F$"+(fl.xMax-fl.xMin+1)+"$"+(fl.zMax-fl.zMin+1)+"$"+elev.floor.fType.name()+"$"+pRel;
				}
			}} else if(line.startsWith("N")) { synchronized(Conf.API_SYNC) { //Denote Elevator Request:
				String[] args = line.split("\\$"); Elevator elev = Conf.elevators.get(args[1]);
				if(elev != null) {
					Floor fl = elev.floor; //Get ready to teleport next player to run /lpmc command:
					lpmcLoc = new Location(fl.world, (fl.xMax/2.0)+(fl.xMin/2.0)+0.5, elev.getLevel()+1.1, (fl.zMax/2.0)+(fl.zMin/2.0)+0.5);
					lpmcMode = 1; Conf.plugin.setTimeout(() -> { lpmcMode = 0; }, 10000); //10 second timeout.
				}
			}} else if(line.startsWith("O")) { synchronized(Conf.API_SYNC) { //Elevator Offset Request:
				String[] args = line.split("\\$"); elevOffset = Integer.parseInt(args[1]); if(elevOffset < 0) elevOffset = 0;
				elevMax = Integer.parseInt(args[2]); if(elevMax < 1) elevMax = 1;
			}}
			
			if(msg != null) sendMsg(msg); if(line.length() > 0) pingTime = 0;
		}/*} catch(Exception e) { err("Read thread error: "+e.getClass().getName()); }*/});
		
		pingThread = new Thread(() -> { while(true) try { //Send keep-alive ping every 2 seconds:
			if(enabled) {
				if(pingSendTime >= 200) { pingSendTime = 0; sendMsg("PING"); }
				if(pingTime > 250) { dbg("Connection timed out!"); closeClient(); }
				pingTime++; pingSendTime++;
			} sleep(10);
		} catch(Exception e) { err("Ping thread error: "+e.getClass().getName()); }});
		
		sThread.start(); sReadThread.start(); pingThread.start(); //Start threads.
	}
	
	private static Player closestPlayer(World world, double x, double z, double maxRange) {
		double pDist = maxRange*maxRange; List<Player> pList = world.getPlayers();
		Player p = null; for(int i=0,l=pList.size(); i<l; i++) {
			double rX = pList.get(i).getLocation().getX()-x, rZ = pList.get(i).getLocation().getZ()-z;
			double dist = (rX*rX)+(rZ*rZ); if(dist <= pDist) { p = pList.get(i); pDist = dist; }
		} return p;
	}
	
	/*public static void tp(Player p, Location loc) {
		Location pl = p.getLocation(); loc.setYaw(pl.getYaw());
		loc.setPitch(pl.getPitch()); p.teleport(loc);
	}*/ //TODO DELETE
	
	private static float map(float input, float minIn, float maxIn, float minOut, float maxOut) {
		return ((input-minIn)/(maxIn-minIn)*(maxOut-minOut))+minOut;
	}
	
	@Override
	public void onDisable() {
		//HandlerList.unregisterAll();
		dbg("§dDisabling plugin."); closeServer();
		sThread.stop(); sThread = null;
		sReadThread.stop(); sReadThread = null;
		pingThread.stop(); pingThread = null;
		Bukkit.getPluginManager().disablePlugin(this);
	}
	
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if(command.getName().equalsIgnoreCase("lpmc") && sender instanceof Player) {
			if(lpmcMode == 1 && lpmcLoc != null) { lpmcMode = 0; ((Player)sender).teleport(lpmcLoc); lpmcLoc = null; }
			else sender.sendMessage("Action timeout expired or no action requested!");
			return true;
		}
		return false;
	}
	
	private boolean startServer() {
		dbg("Starting socket server on port "+PORT);
		try {
			server = new ServerSocket(PORT);
		} catch(Exception e) { err("Could not start server!"); return false; }
		return true;
	}
	
	private void waitForClient() {
		if(server != null) try {
			closeClient(); dbg("Waiting for client..."); //TODO MAYBE USE client.setKeepAlive(true);
			client = server.accept(); client.setTcpNoDelay(true); //.accept() blocks until connection.
			in = new BufferedReader(new InputStreamReader(client.getInputStream()));
			out = new PrintWriter(client.getOutputStream(), true);
		} catch(Exception e) { err("IOException while opening client connection: "+e.getClass().getName()); }
		if(client != null) { dbg("Client connected!"); elevOffset = 0; elevMax = 8; lpmcMode = 0; lpmcLoc = null; enabled = true; }
	}
	
	private void closeClient() {
		if(client != null) try { dbg("Closing client connections."); enabled = false; client.close(); client = null; out = null; in = null; pingTime = 0; pingSendTime = 0; }
		catch(Exception e) { err("Could not close socket!"); return; }
	}
	
	private void closeServer() {
		if(server != null) try { closeClient(); dbg("Disabling socket server."); server.close(); server = null; }
		catch(Exception e) { err("Could not close server!"); return; }
	}
	
	private synchronized void sendMsg(String msg) { if(enabled) out.println(msg); }
	
	private String readClientLine() {
		try { String str = in.readLine(); return str!=null?str:""; }
		catch(Exception e) { err("Socket read failed! Disconnecting client..."); closeClient(); }
		return "";
	}
	
	private static void err(String str, Exception e) {
		String msg = MSG_BADGE+" §b"+str+"§e: §c"+e.getClass().getSimpleName();
		if(e.getMessage() != null) msg += " \""+e.getMessage()+"\"";
		Bukkit.getConsoleSender().sendMessage(msg); e.printStackTrace();
		Collection<? extends Player> players = Bukkit.getOnlinePlayers();
		for(Player p : players) if(p.hasPermission(Main.PERM_ADMIN)) p.sendMessage(msg);
	} private static void err(String str) {
		String msg = MSG_BADGE+" Error: §c"+str; Bukkit.getConsoleSender().sendMessage(msg);
		Collection<? extends Player> players = Bukkit.getOnlinePlayers();
		for(Player p : players) if(p.hasPermission(Main.PERM_ADMIN)) p.sendMessage(msg);
	}
	
	private static void dbg(String str) {
		String msg = MSG_BADGE+" §r"+str; Bukkit.getConsoleSender().sendMessage(msg);
		Collection<? extends Player> players = Bukkit.getOnlinePlayers();
		for(Player p : players) if(p.hasPermission(Main.PERM_ADMIN)) p.sendMessage(msg);
	}
	
	private static boolean sleep(long millis) {
		try { Thread.sleep(millis); } catch(InterruptedException e) { return true; }
		return false;
	}
}