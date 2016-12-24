package com.pecacheu.lpclient;

import java.awt.AWTException;

//LaunchPad Library LaunchPadLCD Demo.

public class LaunchPadLCD {
	private LaunchPad lp; private ScreenCapture scr;
	private SmoothDisplay disp; private Thread lcdThread;
	
	public LaunchPadLCD(LaunchPad lp) {
		this.lp = lp; scr = null; disp = new SmoothDisplay(10, 10, 8);
		
		try { scr = new ScreenCapture(new SmoothRect(0, 0, 10, 10, 0)); }
		catch(AWTException e) { e.printStackTrace(); System.exit(0); }
		disp.addShape(scr);
		
		lcdThread = new Thread(() -> { while(true) {
			redraw(); try { Thread.sleep(15); } catch(InterruptedException e)
			{ System.out.println("[LaunchPadLCD] Loop Interrupted! Stopping."); return; }
		}}); lcdThread.start();
	}
	
	private void redraw() {
		scr.capture(); disp.update();
		disp.redraw((x,y,color) -> { lp.setLedRgb(new Position(x, y).toLed(), color); });
	}
	
	protected void finalize() throws Throwable {
		lp.setListener(null); lp.flush(); lp.close(); lp = null;
		lcdThread.interrupt(); lcdThread = null;
	}
}