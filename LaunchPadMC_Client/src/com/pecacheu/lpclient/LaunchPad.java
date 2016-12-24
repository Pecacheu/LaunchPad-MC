package com.pecacheu.lpclient;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiDeviceReceiver;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;

import rainbowvis.Rainbow;

public class LaunchPad {
	public static boolean DEBUG = false;
	private MidiDevice.Info inInfo; private MidiDevice.Info outInfo;
	private MidiDevice devIn; private MidiDevice devOut;
	private MidiInput midiIn; private MidiDeviceReceiver midiOut;
	private volatile ChuList<MidiMessage> cue = new ChuList<MidiMessage>(1024);
	private Thread midiTx;
	
	//----------------------------------------- Get Devices: -----------------------------------------
	
	public static void listDevices() {
		MidiDevice.Info[] iList = MidiSystem.getMidiDeviceInfo();
		for(int i=0,l=iList.length; i<l; i++) {
			msg("["+i+"] "+iList[i].getVendor()+": "+iList[i].getName()+","+iList[i].getVersion());
			msg("-- DESC: "+iList[i].getDescription());
		}
	}
	
	public static MidiDevice.Info[] getDevice() {
		MidiDevice.Info[] iList = MidiSystem.getMidiDeviceInfo(); MidiDevice.Info[] oList = new MidiDevice.Info[2];
		for(int i=0,l=iList.length; i<l; i++) if(iList[i].getName().equals("MIDIIN2 (Launchpad Pro)")) oList[0] = iList[i];
		else if(iList[i].getName().equals("MIDIOUT2 (Launchpad Pro)")) oList[1] = iList[i];
		return (oList[0]!=null&&oList[1]!=null)?oList:null;
	}
	
	//----------------------------------------- Main Functions: -----------------------------------------
	
	public LaunchPad(MidiDevice.Info inInfo, MidiDevice.Info outInfo) throws MidiUnavailableException {
		this.inInfo = inInfo; this.outInfo = outInfo; init();
	}
	
	private void init() throws MidiUnavailableException {
		devIn = MidiSystem.getMidiDevice(inInfo); devIn.open();
		devOut = MidiSystem.getMidiDevice(outInfo); devOut.open();
		midiIn = new MidiInput(); devIn.getTransmitter().setReceiver(midiIn);
		midiOut = (MidiDeviceReceiver)devOut.getReceiver();
		
		//MIDI Message Sender Thread:
		midiTx = new Thread(() -> { while(true) {
			int size; while((size=cue.size()) > 0) {
				MidiMessage msg; synchronized(this) { msg = cue.get(0); cue.remove(0); } midiOut.send(msg, -1);
				if(size > 10000) { //Keep array under 10,000...
					synchronized(this) { size=cue.size(); while(size > 50) { cue.remove(size-1); size--; }}
					debug("Buffer had to be force-cleared!");
				} if(Thread.interrupted()) return;
			} if(Thread.interrupted()) return;
		}}); midiTx.setPriority(1); midiTx.start();
		
		programMode(); setAll(-1); //Reset LaunchPad Lights.
	}
	
	public void setListener(MidiFunc listener) {
		midiIn.onMidi = listener;
	}
	
	public void flush() throws InterruptedException {
		debug("Flushing Output Buffer..."); int size=0;
		while((size=cue.size()) > 0) { Thread.sleep(10); debug("Size: "+size); }
		debug("Cleared!");
	}
	
	public void reset(boolean clearCache) throws MidiUnavailableException {
		MidiFunc listener = midiIn.onMidi; close(); if(clearCache) cue.clear();
		init(); setListener(listener);
	} public void reset() throws MidiUnavailableException { reset(true); }
	
	public void close() {
		midiTx.interrupt(); midiTx = null;
		midiIn.close(); devIn.close(); midiIn = null; devIn = null;
		midiOut.close(); devOut.close(); midiOut = null; devOut = null;
	}
	
	//----------------------------------------- MIDI Communication: -----------------------------------------
	
	private void send(int cmd, int ch, int data1, int data2) {
		ShortMessage msg = new ShortMessage();
		try { msg.setMessage(cmd, ch, data1, data2); } catch (InvalidMidiDataException e)
		{ debug("Error in LaunchPad:Send: Mal-formatted MIDI data!"); } synchronized(this) { cue.add(msg); } //midiOut.send(msg, -1);
	}
	
	private void sysex(ChuList<Integer> data) {
		data.addAll(0, new Integer[]{0,32,41,2,16}); data.add(247);
		SysexMessage msg = new SysexMessage(); byte[] dBytes = new byte[data.length];
		for(int i=0,l=data.length; i<l; i++) dBytes[i] = data.get(i).byteValue();
		try { msg.setMessage(240, dBytes, dBytes.length); synchronized(this) { cue.add(msg); }} catch
		(InvalidMidiDataException e) { debug("Error in LaunchPad:SysEx: Invalid data!"); }
	} private void sysex(Integer... data) { sysex(new ChuList<Integer>(data)); }
	
	//Direct version for very long data.
	private void sysexDirect(ChuList<Byte> data) {
		data.addAll(0, new Byte[]{0,32,41,2,16}); data.add((byte)247);
		SysexMessage msg = new SysexMessage(); byte[] dBytes = new byte[data.length];
		for(int i=0,l=data.length; i<l; i++) dBytes[i] = data.get(i);
		try { msg.setMessage(240, dBytes, dBytes.length); midiOut.send(msg, -1); } catch
		(InvalidMidiDataException e) { debug("Error in LaunchPad:SysEx: Invalid data!"); }
	}
	
	//----------------------------------------- Mode Switching: -----------------------------------------
	
	public void programMode() { sysex(44, 3); }
	public void faderMode() { sysex(44, 2); }
	//TODO Add other modes.
	
	//----------------------------------------- LED Control Functions: -----------------------------------------
	
	//Sets LED to specified color.
	public void setLed(int led, int color) { //led: 1-99, color: 0-127
		if(led == -1) return; sysex(10, led, color);
	} public void setLedRgb(int led, int rgb) { //led: 1-99, rgb: 0-255
		int r = (rgb & 0xFF), g = ((rgb & 0xFF00) >> 8), b = ((rgb & 0xFF0000) >> 16);
		if(led == -1) return; sysex(11, led, (int)(r/4.0), (int)(g/4.0), (int)(b/4.0)); //Convert to 6-bit 0-63 values.
	}
	
	//Sets a whole column of LEDs.
	public void setColumn(int column, ChuList<Integer> colors) { //column: 0-9, colors: 10 of 0-127
		colors.addAll(0, new Integer[]{12,column}); sysex(colors);
	} public void setColumn(int column, Integer... colors) { ChuList<Integer>
	data = new ChuList<Integer>(colors); setColumn(column, data); }
	
	//Sets a whole row of LEDs.
	public void setRow(int row, ChuList<Integer> colors) { //row: 0-9, colors: 10 of 0-127
		colors.addAll(0, new Integer[]{13,row}); sysex(colors);
	} public void setRow(int row, Integer... colors) { ChuList<Integer>
	data = new ChuList<Integer>(colors); setRow(row, data); }
	
	//Quickly Flashes LED.
	//The side LED is index 99.
	public void flashLed(int led, int color) { //led: 1-99, color: 0-127
		if(led == -1) return; sysex(35, led, color);
	}
	
	//Smoothly Fades LED.
	public void pulseLed(int led, int color) { //led: 1-99, color: 0-127
		if(led == -1) return; sysex(40, led, color); //vvv speed: cycles-per-second, led: 1-99, rgb: 0-255
	} public Thread pulseLed(int led, int speed, int steps, int pause, int rgb1, int rgb2) {
		int r1 = (rgb1 & 0xFF), g1 = ((rgb1 & 0xFF00) >> 8), b1 = ((rgb1 & 0xFF0000) >> 16);
		int r2 = (rgb2 & 0xFF), g2 = ((rgb2 & 0xFF00) >> 8), b2 = ((rgb2 & 0xFF0000) >> 16);
		if(led == -1) return null;
		Thread t = new Thread(() -> {
			//final int LOOP = 100, DELAY = 10, PAUSE = 500;
			final int LOOP = steps-1, FREQ = Math.round((float)speed/steps*1000.f);
			boolean dir = true; while(true) {
				for(int c=(dir?0:LOOP); dir?(c<=LOOP):(c>=0); c+=(dir?1:-1)) {
					int r = Math.round(map(c, 0, LOOP, r1, r2));
					int g = Math.round(map(c, 0, LOOP, g1, g2));
					int b = Math.round(map(c, 0, LOOP, b1, b2));
					setLedRgb(led, rgb(r, g, b));
					try { Thread.sleep(FREQ); } catch (InterruptedException e) { return; }
				}
				dir = !dir; try { Thread.sleep(pause); } catch (InterruptedException e) { return; }
			}
		}); t.start(); return t;
	} //Keeps update timing consistent rather than step count:
	public Thread pulseLedConst(int led, int speed, int freq, int pause, int rgb1, int rgb2) {
		int r1 = (rgb1 & 0xFF), g1 = ((rgb1 & 0xFF00) >> 8), b1 = ((rgb1 & 0xFF0000) >> 16);
		int r2 = (rgb2 & 0xFF), g2 = ((rgb2 & 0xFF00) >> 8), b2 = ((rgb2 & 0xFF0000) >> 16);
		if(led == -1) return null;
		Thread t = new Thread(() -> {
			final int LOOP = Math.round(speed/(freq/1000.f))-1;
			boolean dir = true; while(true) {
				for(int c=(dir?0:LOOP); dir?(c<=LOOP):(c>=0); c+=(dir?1:-1)) {
					int r = Math.round(map(c, 0, LOOP, r1, r2));
					int g = Math.round(map(c, 0, LOOP, g1, g2));
					int b = Math.round(map(c, 0, LOOP, b1, b2));
					setLedRgb(led, rgb(r, g, b));
					try { Thread.sleep(freq); } catch (InterruptedException e) { return; }
				}
				dir = !dir; try { Thread.sleep(pause); } catch (InterruptedException e) { return; }
			}
		}); t.start(); return t;
	}
	//Sets all LEDs at once, including side buttons.
	public void setAll(int color) { //color: 0-127
		if(color == -1) { color = 0; setLed(99, rgb(10, 10, 10)); }
		sysex(14, color);
	}
	
	//Updates all LEDs on grid, optionally including side buttons.
	public void updateAll(int[][] frame) { //sides must be true for non-rgb-mode version, frame: 10x10 of color
		int colLen = frame[0].length, rowLen = frame.length; if(colLen != 10 && colLen != 8) return; if(rowLen != 10 && rowLen != 8) return;
		for(int c=0; c<colLen; c++) for(int r=0; r<rowLen; r++) send(ShortMessage.NOTE_ON, 0, new Position(r, c).toLed(), frame[r][c]);
	} //RGB Mode Version:
	public void updateAllRgb(boolean sides, int[][] frame) { //sides: YES=10x10 or NO=8x8, frame: 10x10 or 8x8 of rgb
		int colLen = frame[0].length, rowLen = frame.length; if(colLen != 10 && colLen != 8) return; if(rowLen != 10 && rowLen != 8) return;
		ChuList<Byte> data = new ChuList<Byte>(colLen * rowLen * 3); data.add((byte)15); data.add((byte)(sides?0:1));
		for(int c=0; c<colLen; c++) for(int r=0; r<rowLen; r++) { int rgb = frame[r][c];
			int red = (rgb & 0xFF), green = ((rgb & 0xFF00) >> 8), blue = ((rgb & 0xFF0000) >> 16);
			data.push((byte)red); data.push((byte)green); data.push((byte)blue);
		}
		sysexDirect(data); //TODO Does sysex direct work well?
	}
	
	//Scrolls ASCII text across LaunchPad.
	//Put [1-7] (including brackets) in string to change speed mid-scroll. Default speed is 4.
	public void scrollText(int color, String text, boolean loop) {
		ChuList<Integer> data = new ChuList<Integer>(text.length());
		for(int i=0,l=text.length(); i<l; i++) { char c = text.charAt(i);
			if(c == '[' && text.substring(i, i+3).matches("^\\[[1-7]\\]$"))
			{ data.push(Integer.parseInt(text.substring(i+1,i+2))); i += 2; }
			else data.push((int)c);
		}
		data.addAll(0, new Integer[]{20,color,loop?1:0}); sysex(data);
	} public void scrollText(int color, String text) { scrollText(color, text, false); }
	
	//Draws built-in fader controls.
	//You must set LaunchPad to fader mode first.
	public void fader(int row, boolean type, int color, int value) { //row: 0-7, type: VOLUME or PAN, color: 0-127, value: 0-127
		sysex(43, row, type?1:0, color, value);//TODO value or initialValue?
	}
	
	//----------------------------------------- Client-Side Macros: -----------------------------------------
	
	//Do a screen saver animation.
	//Run .interrupt() on returned thread to stop.
	public Thread screenSaverA() {
		Thread t = new Thread(() -> { while(true) {
			send(ShortMessage.NOTE_ON, 0, (int)(Math.random()*127), (int)(Math.random()*127));
			try { Thread.sleep(20); } catch (InterruptedException e) { setAll(-1); setAll(-1); return; }
		}}); t.start(); return t;
	}
	
	//As you can see, this one is a little more involved.
	public Thread screenSaverB() {
		String[][] spectrums = {
			{"black", "black", "red", "green", "blue", "black", "black"},
			{"navy", "blue", "aqua", "teal", "lime", "green", "maroon", "red", "silver"},
			{"black", "purple", "fuchsia", "lime", "green", "orange", "black"},
			{"white", "yellow", "white", "teal", "white", "black"},
			{"black", "grey", "orange", "olive", "black"},
		};
		
		Thread t = new Thread(() -> {
			Rainbow rb = new Rainbow();
			try { rb.setSpectrum(spectrums[0]); } catch(Exception e) { e.printStackTrace(); }
			try { rb.setNumberRange(0, 16); } catch(Exception e) { e.printStackTrace(); }
			
			boolean dir = true; int angle = 0;
			
			while(true) {
				for(float u=17*(dir?-1:1); (dir?(u < 17):(u > -17)); u+=(dir?0.4f:-0.4f)) {
					for(int i=0; i<17; i++) {
						String c = rb.colorAt(i+u);
						int red = Integer.parseInt(c.substring(0,2), 16);
						int green = Integer.parseInt(c.substring(2,4), 16);
						int blue = Integer.parseInt(c.substring(4,6), 16);
						for(int h=0; h<10; h++) {
							int pos = 0;
							if(angle == 0) pos = new Position(i+h-8,h).toLed();
							else if(angle == 1) pos = new Position(i-h+1,h).toLed();
							else if(angle == 2) pos = new Position(i,h).toLed();
							else if(angle == 3) pos = new Position(h,i).toLed();
							if(pos < 99) setLedRgb(pos, rgb(red, green, blue));
							if(pos == 4) setLedRgb(99, rgb(red, green, blue));
						}
					}
					try { Thread.sleep(25); } catch(InterruptedException e) { setAll(-1); setAll(-1); return; }
				}
				
				if(Math.random() >= 0.5) dir = !dir;
				angle = (int)Math.round(Math.random()*3);
				
				try {
					double rand = Math.random(); int div = spectrums.length;
					for(int i=0; i<div; i++) if(rand < 1.0/div*(i+1)) { rb.setSpectrum(spectrums[i]); break; }
				} catch(Exception e) { e.printStackTrace(); }
				
				try { Thread.sleep(10); } catch(InterruptedException e) { setAll(-1); setAll(-1); return; }
			}
		}); t.start(); return t;
	}
	
	//----------------------------------------- Utility Functions: -----------------------------------------
	
	//Compress RGB to a single number for updateAll function.
	public static int rgb(int r, int g, int b) {
		return ((r & 0xFF) + ((g << 8) & 0xFF00) + ((b << 16) & 0xFF0000));
	}
	
	public static int fromBGR(int bgr) {
		return rgb(((bgr & 0xFF0000) >> 16), ((bgr & 0xFF00) >> 8), (bgr & 0xFF));
	}
	
	//Create array of 0s for use with updateAll.
	public static int[][] newUpdateFrame(boolean sides, int color) {
		int size = sides?10:8; int[][] arr = new int[size][size];
		for(int i=0; i<size; i++) { int[] subArr = new int[size]; for(int
		b=0; b<size; b++) subArr[b] = color; arr[i] = subArr; } return arr;
	} public static int[][] newUpdateFrame(boolean sides) { return newUpdateFrame(sides, 0); }
	
	//Set whole row of leds in an updateAll array:
	public static void setRow(int[][] frameRef, int row, int color) {
		for(int i=0; i<10; i++) frameRef[i][row] = color;
	} public static void setRow(int[][] frameRef, int row, ChuList<Integer> colors) {
		for(int i=0; i<10; i++) frameRef[i][row] = colors.get(i);
	}
	
	//Set whole row of leds in an updateAll array:
	public static void setColumn(int[][] frameRef, int column, int color) {
		for(int i=0; i<10; i++) frameRef[column][i] = color;
	} public static void setColumn(int[][] frameRef, int column, ChuList<Integer> colors) {
		for(int i=0; i<10; i++) frameRef[column][i] = colors.get(i);
	}
	
	/*//Convert 2D ([x][y]) Array to 1D.
	public ChuList<Integer> to1dArr(ChuList<ChuList<Integer>> data) {
		int colLen = data.get(0).length, rowLen = data.length; ChuList<Integer> list = new ChuList<Integer>(colLen * rowLen);
		for(int c=0; c<colLen; c++) for(int r=0; r<rowLen; r++) list.push(data.get(r).get(c));
		return list;
	}
	
	//Convert 2D ([y][x]) Array to 1D.
	public ChuList<Integer> to1dArrYX(ChuList<ChuList<Integer>> data) {
		ChuList<Integer> list = new ChuList<Integer>();
		for(int i=0,l=data.length; i<l; i++) list.addAll(data.get(i));
		return list;
	}*/
	
	//Pecacheu's ultimate unit translation formula:
	//This Version -- Bounds Checking: NO, Rounding: FLOAT, Max/Min Switching: NO
	public static float map(float input, float maxIn, float maxOut) {
		return input/maxIn*maxOut;
	} public static float map(float input, float minIn, float maxIn, float minOut, float maxOut) {
		return ((input-minIn)/(maxIn-minIn)*(maxOut-minOut))+minOut;
	}
	
	//Generate a bar of leds based on number.
	public static ChuList<Integer> ledBar(float val, float max, int colorOn, int colorOff, boolean sides) {
		ChuList<Integer> list = new ChuList<Integer>(); int leds = Math.round(val/max*(sides?11.f:9.f));
		for(int i=1,l=sides?11:9; i<l; i++) list.push(i<=leds?colorOn:colorOff);
		if(!sides) { list.add(0, 0); list.add(0); } return list;
	} public static ChuList<Integer> ledBar(int leds, int colorOn, int colorOff, boolean sides) {
		ChuList<Integer> list = new ChuList<Integer>(); int max = sides?11:9; if(leds>max-1)
		leds = max-1; for(int i=1; i<max; i++) list.push(i<=leds?colorOn:colorOff);
		if(!sides) { list.add(0, 0); list.add(0); } return list;
	}
	
	private static void msg(String msg) {
		synchronized(System.out) { System.out.println("[LaunchPad Library] "+msg); }
	}
	
	private static void debug(String msg) {
		if(DEBUG) synchronized(System.out) { System.out.println("[LaunchPad Library] "+msg); }
	}
	
	//TODO Add MIDI Clock for changing pulse speed.
	//TODO Add MIDI input.
}

class MidiInput implements MidiDeviceReceiver {
	public MidiFunc onMidi = null;
	
	@Override
	public void close() {}
	
	@Override
	public void send(MidiMessage msg, long timeStamp) {
		if(onMidi != null) onMidi.call(msg);
	}
	
	@Override
	public MidiDevice getMidiDevice() {
		return null;
	}
}

@FunctionalInterface
interface MidiFunc {
	public void call(MidiMessage msg);
}

class Position {
	final int x; final int y;
	
	public Position(int x, int y) {
		this.x = x; this.y = y;
	}
	
	//Creates new Position while keeping X and Y within bounds.
	public static Position keepInBounds(int x, int y) {
		if(x < 0) x = 0; if(x > 9) x = 9;
		if(y < 0) y = 0; if(y > 9) y = 9;
		return new Position(x, y);
	}
	
	//Creates new Position based on LED index.
	public static Position fromLed(int led) {
		return new Position((int)(led%10.f), (int)(led/10.f));
	}
	
	//Calculates LED index for position. Returns -1 if out of bounds.
	public int toLed() {
		if(x < 0) return -1; if(x > 9) return -1;
		if(y < 0) return -1; if(y > 9) return -1;
		return x+(10*y);
	}
}

//Available Pad Colors For All RGB LaunchPad Variants:

class PadColor {
	//RGB Mode Colors:
	/*int off = LaunchPad.rgb(0, 0, 0);
	int red = LaunchPad.rgb(255, 0, 0);
	int green = LaunchPad.rgb(0, 255, 0);
	int white = LaunchPad.rgb(255, 255, 255);
	int yellow = LaunchPad.rgb(127, 127, 0);
	int lightBlue = LaunchPad.rgb(0, 134, 206);*/
	
	//Whites:
	static int off = 0, veryDarkGrey = 71, darkGrey = 1, grey = 2, white = 3;
	static int coolGrey = 103, coolWhite = 119, warmWhite = 8;
	
	//Reds:
	static int washedOutRed = 4, red = 5, midRed = 6, darkRed = 7;
	
	//Greens:
	static int softGreen = 20, green = 87, midGreen = 22, darkGreen = 64;
	static int lightLime = 16, lime = 17, midLime = 18, darkLime = 19;
	static int lightMint = 114, mint = 29, midMint = 30, darkMint = 31;
	
	//Blues:
	static int skyBlue = 44, blue = 45, midBlue = 46, darkBlue = 47;
	static int lightCyan = 32, cyan = 33, midCyan = 34, darkCyan = 35;
	static int lightCloud = 40, cloud = 41, midCloud = 42, darkCloud = 43;
	
	//Purples & Pinks:
	static int lightMagenta = 52, magenta = 53, midMagenta = 54, darkMagenta = 55;
	static int lightPurple = 48, purple = 49, midPurple = 50, darkPurple = 51;
	static int lightPink = 56, pink = 57, midPink = 58, darkPink = 59;
	
	//Yellows & Oranges:
	static int peach = 12, yellow = 13, midYellow = 14, darkYellow = 125;
	static int orangeYellow = 126, yellowGreen = 74, darkYellowGreen = 15;
	
	static int tangerine = 96, lightOrange = 9, orange = 84, midOrange = 10, darkOrange = 11;
	static int deepOrange = 60, brown = 61, darkBrown = 83;
	
	//lp.setRow(1, new Integer[]{off,veryDarkGrey,darkGrey,grey,white,coolGrey,coolWhite,warmWhite});
	//lp.setRow(3, new Integer[]{off,102,81,116}); //Colors of interest.
}