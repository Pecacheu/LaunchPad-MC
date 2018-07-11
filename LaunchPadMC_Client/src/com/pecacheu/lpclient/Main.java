//This work is licensed under a GNU General Public License. Visit http://gnu.org/licenses/gpl-3.0-standalone.html for details.
//LaunchPadMC Server. Copyright (©) 2016, Pecacheu (Bryce Peterson, bbryce.com).

package com.pecacheu.lpclient;

//Intended for LPMC v1.3.0

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.ShortMessage;

public class Main {
	static int PORT = 25585;
	static String HOST = "localhost";
	LaunchPad lp;
	
	volatile boolean enabled = false;
	volatile Socket client = null;
	volatile Thread readThread = null;
	volatile Thread cThread = null;
	volatile Thread cReadThread = null;
	volatile Thread pingThread = null;
	volatile BufferedReader in = null;
	volatile PrintWriter out = null;
	volatile int pingTime = 0;
	volatile int pingSendTime = 0;
	//TODO NEEDED? ... volatile boolean reset = false;
	
	volatile int noActivity = 0;
	Thread sleep; Object slSync = new Object();
	
	//Elevator Data Storage:
	volatile String[] eKeys = {}; volatile int[] eFloors = {};
	volatile int[] eDir = {}; volatile int[] eLevel = {}; //TODO Check for sync in all uses. Maybe they don't have to be volatile?
	volatile int viewMode = 0, lOffset = 0, fMax = 0, selElev = -1;
	volatile boolean vmToggle = true, wait = true;
	
	//SelElev Data Storage:
	volatile int[] csStatus = {}; volatile int pCount = 0;
	volatile boolean doors = false; volatile boolean noRedraw = false;
	
	//Floor Data Storage:
	volatile int flXSize = 0, flZSize = 0;
	volatile String fType = ""; volatile int pRel = 0;
	
	public static void main(String[] args) {
		dbg("LaunchPadMC Client Program\n");
		if(loadConfig()) new Main();
	}
	
	private static boolean loadConfig() { try {
		final String CONF = ChuConf.jarDir()+"config";
		if(ChuConf.confExists(CONF)) {
			ChuConf conf = ChuConf.load(CONF);
			Object d = conf.getProp("debug"), h = conf.getProp("host"), p = conf.getProp("port");
			if(!(d instanceof String)) { err("Config read error: Debug must be boolean!"); return false; }
			if(!(h instanceof String)) { err("Config read error: Host must be string!"); return false; }
			if(!(p instanceof Integer)) { err("Config read error: Port must be integer!"); return false; }
			LaunchPad.DEBUG = Boolean.valueOf((String)d); HOST = ((String)h); PORT = ((Integer)p);
		} else saveConf(CONF); //Create new config.
		return true;
	} catch (Exception e) { err("Error while loading config file",e); } return false; }
	
	private static void saveConf(String path) throws Exception {
		ChuConf conf = new ChuConf();
		conf.setProp("debug", String.valueOf(LaunchPad.DEBUG));
		conf.setProp("host", HOST); conf.setProp("port", PORT);
		conf.save(path);
	}
	
	public Main() {
		//Init Midi:
		//LaunchPad.listDevices();
		MidiDevice.Info[] devList = LaunchPad.getDevice(); if(devList == null) { err("No LaunchPad device found!"); return; }
		try { lp = new LaunchPad(devList[0], devList[1]); } catch(MidiUnavailableException e) { err("Could not open LaunchPad device!"); return; }
		
		lp.scrollText(PadColor.midBlue, HOST+":"+PORT);
		
		//lp.faderMode(); lp.fader(0, false, 20, 100); lp.fader(1, false, 22, 100);
		//lp.fader(2, false, 0, 0); lp.fader(3, false, 0, 0); lp.fader(4, false, 0, 0);
		//lp.fader(5, false, 0, 0); lp.fader(6, false, 0, 0); lp.fader(7, true, 65, 80);
		
		//Integer[] tLeds = {0,72,72,72,72,72,72,72,72,0};
		//tLeds[1] = 75; lp.setRow(0, tLeds);
		
		//lp.setColumn(1, LaunchPad.ledBar(0.5, 5, 75, 72, false));
		//lp.setColumn(2, LaunchPad.ledBar(2.49, 5, 75, 72, false));
		//lp.setColumn(3, LaunchPad.ledBar(4.2, 5, 75, 72, true));
		//lp.setColumn(4, LaunchPad.ledBar(2.5, 5, 75, 72, true));
		
		//lp.setStatusLed(13);
		
		//lp.flashLed(LaunchPad.posToLed(1,0), 20);
		//if(true) { disable(); return; }
		//lp.scrollText(20, "Hello!");
		
		//lp.setLed(10, 72);
		
		//Thread t = lp.screenSaverB();
		
		//try { lp.flush(); } catch (InterruptedException e) {} System.exit(0); //FLUSH/EXIT TEMP
		
		//lp.setLed(10, 72);
		
		//Thread pulse = lp.pulseLed(99, 1, 80, 100, yellow, lightBlue);
		
		/*new Thread(() -> { while(true) try {
			synchronized(slSync) {
				if(noActivity == 0) if(sleep != null) { sleep.interrupt(); sleep = null; lp.reset(); lp.setAll(-1); /*pulse.resume();* }
				if(noActivity >= 40) { if(sleep == null) { sleep = lp.screenSaverB(); /*pulse.suspend();* }} else noActivity++;
			} sleep(500);
		} catch(Exception e) { e.printStackTrace(); }}).start();*/
		
		cThread = new Thread(() -> { while(true) try { //Send data to server:
			if(!enabled) { while(wait) {} redraw(); connectToServer(); }
			
			//Send query requests every 500ms.
			synchronized(this) { if(selElev != -1) { sendMsg((viewMode==0?"Q$":"F$")+eKeys[selElev]); }}
			
			sleep(500);
		} catch(Exception e) { err("Socket thread error: "+e.getClass().getName()); }});
		
		readThread = new Thread(() -> { while(true) try { //Read console commands:
			String cmdLine = waitForCmdLine(); //Wait for new line.
			if(cmdLine != null) { dbg("Commnad: "+cmdLine); if(cmdLine.equalsIgnoreCase("exit")) disable(); }
		} catch(Exception e) { err("Console thread error: "+e.getClass().getName()); }});
		
		cReadThread = new Thread(() -> { while(true) try { //Read lines from server:
			if(enabled) { String line = readServerLine(); //if(line.length() > 0 && !line.equals("PING")) dbg("Server Msg: '"+line+"'");
			
			if(line.startsWith("E")) { synchronized(this) { //Major Elevator Data Update:
				String[] args = line.split("\\$");
				
				eKeys = new String[args.length-1]; eFloors = new int[args.length-1];
				eDir = new int[args.length-1]; eLevel = new int[args.length-1];
				
				int eLen = args.length-1; if(eLen > 8) eLen = 8; //Keep length at 8 elevators.
				
				for(int i=0; i<eLen; i++) {
					String[] elevData = args[i+1].split("&");
					eKeys[i]   = elevData[0]; //eID
					eFloors[i] = Integer.parseInt(elevData[1]); //Level Count
					eDir[i]    = Integer.parseInt(elevData[2]); //Movement Direction (0=not moving, 1/2=moving up/down)
					eLevel[i]  = Integer.parseInt(elevData[3]); //Current Level
				}
				if(selElev >= eKeys.length) selElev = -1;
				
				fMax = 0; for(int i=0,l=eFloors.length; i<l; i++) if(eFloors[i] > fMax) fMax = eFloors[i]; //Find tallest elevator.
				fMax -= 8; if(fMax < 0) fMax = 0; //Keep offset within fMax-8, but above -1.
				
				if(viewMode == 0) doRedraw();
			}} else if(line.startsWith("Q")) { synchronized(this) { if(selElev != -1) { //Elevator Data Query Response:
				String[] args = line.split("\\$");
				
				String[] csData = args[1].split("&"); int csLen = csData.length;
				if(csData[0].length() == 0) csLen = 0; csStatus = new int[csLen];
				
				for(int i=0; i<csLen; i++) csStatus[i] = Integer.parseInt(csData[i]); //Call Sign Info
				
				pCount = Integer.parseInt(args[2]); //Player Count
				doors = args[3].equals("O"); //Door Status
				
				if(viewMode == 0) doRedraw();
			}}} else if(line.startsWith("F")) { synchronized(this) { if(selElev != -1 && (viewMode == 1 || viewMode == 2)) { //Floor Dimension Query Response:
				String[] args = line.split("\\$");
				
				flXSize = Integer.parseInt(args[1]); flZSize = Integer.parseInt(args[2]);
				fType = args[3]; pRel = Integer.parseInt(args[4]); doRedraw();
			}}}
			
			if(line.length() > 0) pingTime = 0;
		}} catch (Exception e) { err("Read thread error: "+e.getClass().getName()); }});
		
		pingThread = new Thread(() -> { while(true) try { //Send keep-alive ping every 2 seconds:
			if(enabled) {
				if(pingSendTime >= 200) { pingSendTime = 0; sendMsg("PING"); }
				if(pingTime > 250) { dbg("Connection timed out!"); closeClient(); }
				pingTime++; pingSendTime++;
			} sleep(10);
		} catch(Exception e) { err("Ping thread error: "+e.getClass().getName()); }});
		
		cThread.start(); readThread.start(); cReadThread.start(); pingThread.start(); //Start threads.
		
		//Set LaunchPad MIDI Listener:
		lp.setListener((msg) -> {
			synchronized(slSync) { noActivity = 0; if(sleep != null) return; }
			int status = msg.getStatus(); byte[] data = msg.getMessage();
			boolean isNote = (status == ShortMessage.NOTE_ON || status == ShortMessage.CONTROL_CHANGE);
			if(enabled) { if(isNote) {
				int note = data[1], valRaw = data[2]; Position pos = Position.fromLed(note);
				boolean val = valRaw > 0;
				
				synchronized(this) { if(viewMode == 0) { if(status == ShortMessage.NOTE_ON) { //Square Pads:
					lp.setLed(note, val?PadColor.white:PadColor.off);
					if(!val && pos.x <= eKeys.length && pos.y <= eFloors[pos.x-1]-lOffset) {
						doRedraw(); //Goto floor request:
						if(eDir[pos.x-1] == 0) sendMsg("G$"+eKeys[pos.x-1]+"$"+(pos.y+lOffset-1));
					}
				} else if(pos.y == 0 && pos.x <= eKeys.length) { //Bottom Side Buttons:
					if(val) { lp.setLed(note, PadColor.cloud); selElev = pos.x-1; }
				} else if(note == 10) { //Record Button:
					lp.setLed(note, val?PadColor.red:PadColor.off);
					if(!val) { selElev = -1; doRedraw(); }
				} else if(note == 93) { if(selElev != -1 && eDir[selElev] == 0) { //Left Button:
					lp.setLed(note, PadColor.darkOrange); //Request door close:
					if(!val) { doRedraw(); sendMsg("D$"+eKeys[selElev]+"$"+"C"); }
				}} else if(note == 94) { if(selElev != -1 && eDir[selElev] == 0) { //Right Button:
					lp.setLed(note, PadColor.darkOrange); //Request door open:
					if(!val) { doRedraw(); sendMsg("D$"+eKeys[selElev]+"$"+"O"); }
				}} else if(note == 91) { if(lOffset < fMax) { //Up Button:
					lp.setLed(note, val?PadColor.yellow:PadColor.darkCyan);
					if(!val) { lOffset++; if(lOffset > fMax) lOffset = fMax; doRedraw(); }
				}} else if(note == 92) { if(lOffset > 0) { //Down Button:
					lp.setLed(note, val?PadColor.yellow:PadColor.darkCyan);
					if(!val) { lOffset--; if(lOffset < 0) lOffset = 0; doRedraw(); }
				}} else if(note == 97) { if(selElev != -1) { //Device Button:
					if(val) {
						viewMode = 1; vmToggle = false; lp.setAll(0); lp.setLed(note, PadColor.magenta);
						new Thread(() -> { sleep(600); synchronized(this) { vmToggle = true; }}).start();
					} else lp.setLed(note, PadColor.magenta);
				}} else if(note == 96) { if(selElev != -1) { //Note Button:
					lp.setLed(note, val?PadColor.yellow:PadColor.darkYellow); noRedraw = val; //Denote elevator.
					if(val) lp.scrollText(PadColor.yellow, "Run /lpmc!"); else sendMsg("N$"+eKeys[selElev]);
				}}} else if(viewMode == 1) { if(note == 97) { //Device Button, Floor View Mode:
					if(!val) {
						if(vmToggle) { viewMode = 0; lp.setAll(0); doRedraw(); } else vmToggle = true;
					} else lp.setLed(note, PadColor.magenta);
				}}}
			}} else {
				if(isNote) {
					int note = data[1];
					if(note == 8) disable();
				}
				if(wait) wait = false;
				/* else if(status == SysexMessage.SYSTEM_EXCLUSIVE) {
					if(data.length == 8 && data[6] == 21) wait = false;
				}*/
			}
			
			/*int note = data[1]; int val = data[2];
			if(status == ShortMessage.NOTE_ON) {
				System.out.println("Got note on "+note+": "+val);
				//lp.setLed(Position.fromLed(note).toLed(), val>0?PadColor.white:PadColor.off);
			} else if(status == ShortMessage.POLY_PRESSURE) System.out.println("Got polyPressure on "+note+": "+val);
			else if(status == ShortMessage.CONTROL_CHANGE) {
				System.out.println("Got ctrlChg on "+note+": "+val);
				//Position pos = Position.fromLed(note);
				//if(note == 10) lp.setLed(note, val>0?PadColor.red:PadColor.off);
				//else if(pos.x == 9) lp.setLed(note, val>0?PadColor.green:PadColor.off);
				//else if(pos.y == 9 && pos.x < 5) lp.setLed(note, val>0?PadColor.skyBlue:PadColor.off);
				//else if(pos.y == 0) {}
				//else lp.setLed(note, val>0?PadColor.yellow:PadColor.off);
			} else if(status == SysexMessage.SYSTEM_EXCLUSIVE) {
				String s=""; for(int i=0,l=data.length; i<l; i++) s += data[i]+",";
				System.out.println("Got sysex {"+s.substring(0,s.length()-1)+"}");
			} else System.out.println("Got message of type: "+msg.getClass().getName());*/
			
			//t.interrupt(); lp.setAll(-1); try { lp.flush(); } catch(Exception e) { e.printStackTrace(); }
			//System.out.println("EXIT"); System.exit(0);
		});
	}
	
	public synchronized void setViewMode(int mode) {
		viewMode = mode; lp.setAll(0); doRedraw();
	} /*public synchronized void redrawIfMode(int mode) {
		if(viewMode == mode) doRedraw();
	}*/ public synchronized void redraw() {
		doRedraw();
	}
	
	private void doRedraw() {
		if(!noRedraw) if(enabled) { if(viewMode == 0) { //Elevator View:
			for(int i=0,l=eKeys.length; i<l; i++) { //Iterate through elevators:
				lp.setColumn(i+1, LaunchPad.ledBar(eFloors[i]-lOffset, PadColor.yellow, PadColor.off, false));
				if(eLevel[i]-lOffset <= 7) if(eDir[i] != 0) lp.flashLed(new Position(i+1, eLevel[i]-lOffset+1).toLed(), PadColor.midGreen);
				else lp.setLed(new Position(i+1, eLevel[i]-lOffset+1).toLed(), PadColor.green);
			}
			//TODO Does not clear columns for elevators that have been removed.
			
			//Selected Elevator Settings:
			lp.setLed(91, lOffset<fMax?PadColor.darkCyan:PadColor.veryDarkGrey);
			lp.setLed(92, lOffset>0?PadColor.darkCyan:PadColor.veryDarkGrey);
			
			if(selElev == -1) {
				lp.setLed(93, PadColor.veryDarkGrey); lp.setLed(94, PadColor.veryDarkGrey);
				lp.setLed(98, PadColor.off); lp.setColumn(9, new ChuList<Integer>(0,0,0,0,0,0,0,0,0));
				lp.setLed(96, PadColor.veryDarkGrey); lp.setLed(97, PadColor.veryDarkGrey);
				csStatus = new int[0]; pCount = 0;
			} else {
				//Door status:
				if(eDir[selElev] != 0) {
					lp.setLed(93, PadColor.veryDarkGrey); lp.setLed(94, PadColor.veryDarkGrey);
				} else {
					lp.setLed(93, doors?PadColor.darkBlue:PadColor.darkOrange);
					lp.setLed(94, doors?PadColor.darkOrange:PadColor.darkBlue);
				}
				
				//Player count:
				lp.setLed(98, pCount>0?(pCount>1?(pCount>2?(pCount>3?PadColor.deepOrange
				:PadColor.orange):PadColor.midOrange):PadColor.darkOrange):PadColor.off);
				
				//Call sign info:
				ChuList<Integer> csRow = new ChuList<Integer>(0,0,0,0,0,0,0,0,0);
				for(int i=0,l=csStatus.length; i<8; i++) {
					int dInd = i+lOffset; if(dInd >= l) break;
					int dat = csStatus[dInd], clr = 0;
					if(dat == 0) clr = PadColor.darkRed; //NOMV
					if(dat == 1) clr = PadColor.coolGrey; //ATLV
					if(dat == 2) clr = PadColor.coolWhite; //M_ATLV
					if(dat == 3) clr = PadColor.darkGreen; //UP
					if(dat == 4) clr = PadColor.mint; //C_UP
					if(dat == 5) clr = PadColor.darkBlue; //DOWN
					if(dat == 6) clr = PadColor.skyBlue; //C_DOWN
					csRow.set(i+1, clr);
				}
				lp.setColumn(9, csRow);
				
				//Other lights:
				lp.setLed(96, PadColor.darkYellow);
				lp.setLed(97, PadColor.darkMagenta);
			}
			
			/*eKeys[i]   = elevData[0]; //eID
			eFloors[i] = Integer.parseInt(elevData[1]); //Level Count
			eDir[i]    = Integer.parseInt(elevData[2]); //Movement Direction (0=not moving, 1/2=moving up/down)
			eLevel[i]  = Integer.parseInt(elevData[3]); //Current Level*/
			
			lp.setRow(0, LaunchPad.ledBar(eKeys.length, PadColor.midGreen, PadColor.midRed, false));
			if(selElev != -1) lp.setLed(selElev+1, PadColor.cloud); lp.setLed(99, PadColor.mint);
		} else if((viewMode == 1 || viewMode == 2) && selElev != -1) { //Floor View:
			int[][] frame = LaunchPad.newUpdateFrame(true, pRel==4?PadColor.veryDarkGrey:0); //lp.setAll(pRel==4?PadColor.veryDarkGrey:0);
			
			//pRel: 0=NORTH, 1=EAST, 2=SOUTH, 3=WEST, 4=OUT_OF_RANGE.
			
			if(pRel != 4) {
				//Draw Compass:
				int north = PadColor.red, south = PadColor.white;
				if(pRel == 0) { LaunchPad.setRow(frame, 0, south); LaunchPad.setRow(frame, 9, north); }
				if(pRel == 1) { LaunchPad.setColumn(frame, 0, north); LaunchPad.setColumn(frame, 9, south); }
				if(pRel == 2) { LaunchPad.setRow(frame, 0, north); LaunchPad.setRow(frame, 9, south); }
				if(pRel == 3) { LaunchPad.setColumn(frame, 0, south); LaunchPad.setColumn(frame, 9, north); }
				
				if(viewMode == 1) { //Floor View:
					//Determine Block Color:
					int bColor = PadColor.brown; switch(fType) {
						case "GLASS": case "STAINED_GLASS": bColor = PadColor.darkGrey; break;
						case "IRON_BLOCK": bColor = PadColor.coolWhite; break;
						case "GOLD_BLOCK": bColor = PadColor.yellow; break;
						case "EMERALD_BLOCK": bColor = PadColor.lime; break;
						case "DIAMOND_BLOCK": bColor = PadColor.lightCloud; break;
					}
					
					//Draw Elevator Floor:
					boolean dir = (pRel == 0 || pRel == 2);
					int xMax = (dir?flXSize:flZSize), zMax = (dir?flZSize:flXSize);
					for(int x=0; x<xMax; x++) for(int z=0; z<zMax; z++) frame[x+1][z+1] = bColor;
					frame[7][9] = PadColor.magenta;
				} else if(viewMode == 2) { //Column Select View:
					
					
					
					frame[0][3] = PadColor.yellow;
				}
			}
			
			lp.updateAll(frame); lp.setLed(99, PadColor.darkGrey);
		}} else {
			lp.setAll(0); lp.pulseLed(99, PadColor.cloud); lp.setLed(8, PadColor.red);
			if(eKeys.length > 0) { //Reset variables:
				eKeys = new String[0]; eFloors = new int[0]; eDir = new int[0]; eLevel = new int[0];
				viewMode = 0; vmToggle = true; lOffset = 0; fMax = 0; selElev = -1;
				csStatus = new int[0]; pCount = 0; doors = false; noRedraw = false;
				flXSize = 0; flZSize = 0; fType = ""; pRel = 0;
			}
		}
	}
	
	//Interface:
	//Up: Scroll up in column view. Lights different color when SEL_ELEV is moving up. For short time after, left side buttons show scrollbar movement animation.
	//Down: Scroll down in column view. Lights different color when SEL_ELEV is moving down. For short time after, left side buttons show scrollbar movement animation.
	//Left: Close Doors. Lights when doors of SEL_ELEV are closed. Disabled when moving.
	//Right: Open Doors. Lights when doors of SEL_ELEV are open. Disabled when moving.
	//Session: ???
	//Note: Indicates SEL_ELEV by temporarily changing floor type to wet_sponge. Lights yellow while this is the case. Disabled when moving.
	//Device: Hold to display SEL_ELEV floor size and material on grid. Background will be veryDarkGrey (or maybe coolGrey).
	//User: Lights dark, mid, or light yellow based on players in SEL_ELEV.
	//Left Side Buttons: Special SEL_ELEV settings. Press delete to delete the elevator. Press record to deselect SEL_ELEV. (Index is set to -1)
	//---- Duplicate is really neat. It will take you into floor view mode to select a position within SEL_ELEV. Existing columns will be marked as red, free space is grey. A new column of signs will be created at the chosen X and Z.
	//Right Side Buttons: Light based on color of SEL_ELEV call signs on level. This also tells you dest floor.
	//Bottom Side Buttons: Light to show total elevator count. Press to set SEL_ELEV.
	//Elevator Columns: Light up to show total and current floors. Current floors flash while moving. Press to go to a floor.
	//Shift Button: Hold to use bottom buttons to select elevator offset. Offset works on server side, not client side, so the change will be applied on the next elevator data packet.
	//---- The 3 left most and 3 right most buttons will be used to achieve 3 different scroll speeds (+-1, +-2, and +-3).
	
	//Controls disabled due to elevator movement light veryDarkGrey.
	
	//Data we need to exchange:
	//All elevator eIDs, level count, isMoving (and dir of movement), and floor height at regular intervals.
	//The selected elevator's eID needs to be indicated when requesting SEL_ELEV related data.
	//SEL_ELEV's call sign data, player count, and door status (always closed if moving), when requested.
	//SEL_ELEV floor dimensions and material when requested.
	//Mark an elevator (by it's eID) by changing fType temporarily.
	//Open/Close Elevator Doors (Opening restarts door timer, closing does not affect it).
	
	//Status LED should reflect current display mode (elevator list, floor view, etc).
	//Also, disable square pad lighting when in floor view mode.
	//Floor view mode will rotate to player's relative (rotated) view of the floor if a player is within a 10 block radius while it's active.
	//Location class has some good vector functions for this. Split things up into 4 orientations based on 4 sections of the radius.
	//Use Location.subtract. If the delta X is greater than the delta Y, etc.
	//North and south will be displayed as grey (maybe darkGrey or veryDarkGrey) for south, and darkRed for north by lighting the side lights on appropriate sides.
	
	public void disable() {
		dbg("Exiting..."); closeClient(); cThread = null; cReadThread = null;
		readThread = null; pingThread = null; lp.setListener(null);
		synchronized(this) { lp.setAll(0); lp.setLed(99, PadColor.darkRed);
		try { lp.flush(); } catch (Exception e) {} lp.close(); System.exit(0); }
	}
	
	public void connectToServer() {
		dbg("Opening new socket to "+HOST+":"+PORT); try {
			closeClient(); client = new Socket(HOST, PORT); client.setTcpNoDelay(true);
			in = new BufferedReader(new InputStreamReader(client.getInputStream()));
			out = new PrintWriter(client.getOutputStream(), true);
		} catch(UnknownHostException e) { err("Could not connect to host!"); return; }
		catch(Exception e) { err("Could not open socket!"); return; }
		if(client != null) { dbg("Socket opened!"); enabled = true; }
	}
	
	public void closeClient() {
		if(client != null) try { dbg("Closing socket."); enabled = false; client.close(); client = null; out = null; in = null; pingTime = 0; pingSendTime = 0; }
		catch(Exception e) { err("Could not close socket!"); return; }
	}
	
	public synchronized void sendMsg(String msg) { if(enabled) out.println(msg); }
	
	public String readServerLine() {
		try { String str = in.readLine(); return str!=null?str:""; }
		catch(Exception e) { err("Socket read failed! Disconnecting from server..."); closeClient(); }
		return "";
	}
	
	public String waitForCmdLine() {
		String line = ""; try { while(true) { while(System.in.available() < 1) sleep(50); int c = System.in.read();
		if(c == '\n' || c == '\r') break; if(c > 0 && c < 65535) line += fromCharCode(c); else break; }}
		catch(Exception e) { err("Error while reading command line: "+e.getClass().getName()); System.exit(1); }
		return line.length()!=0?line:null;
	}
	
	private static void err(String str, Exception e) {
		synchronized(System.out) { System.out.println(str); }
		e.printStackTrace();
	} public static void err(String func, String cause) {
		synchronized(System.out) { System.out.println("Error in "+func+": "+cause); }
	} public static void err(String str) {
		synchronized(System.out) { System.out.println("Error: "+str); } //TODO Does sync help?
	}
	
	public static void dbg(String str) {
		synchronized(System.out) { System.out.println(str); }
	}
	
	public static boolean sleep(long millis) {
		try { Thread.sleep(millis); } catch(InterruptedException e) { return true; }
		return false;
	}
	
	//Emulate JavaScript's fromCharCode Function:
	public static String fromCharCode(int... codePoints) {
		return new String(codePoints, 0, codePoints.length);
	}
}