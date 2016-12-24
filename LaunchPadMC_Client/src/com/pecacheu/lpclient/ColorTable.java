package com.pecacheu.lpclient;

import javax.sound.midi.ShortMessage;

//LaunchPad Library ColorTable Demo.

public class ColorTable {
	private LaunchPad lp; private ChuList<Integer> ex; private int offset = 0;
	
	public ColorTable(LaunchPad lp) { this.lp = lp; ex = new ChuList<Integer>(); init(); }
	public ColorTable(LaunchPad lp, ChuList<Integer> ignoreColors) { this.lp = lp; ex = ignoreColors; init(); }
	
	private void init() {
		lp.pulseLed(99, 44);
		drawColors(); lp.setListener((msg) -> {
			if(msg.getStatus() == ShortMessage.NOTE_ON || msg.getStatus() == ShortMessage.CONTROL_CHANGE) {
				int note = msg.getMessage()[1]; int val = msg.getMessage()[2];
				if(note == 93) { if(val > 0) { lp.setLed(note, 87); moveOffset(-1); } else lp.setLed(note, 0); } //Left Key
				else if(note == 94) { if(val > 0) { lp.setLed(note, 87); moveOffset(1); } else lp.setLed(note, 0); } //Right Key
				else if(val > 0) { System.out.println("[ColorTable] Color ID: "+(note+offset)); lp.setLed(note, 3); } //Other Keys On
				else lp.setLed(note, (ex.indexOf(note+offset) == -1)?(note+offset):0); //Other Keys Off
			}
		});
	}
	
	private void drawColors() {
		for(int i=1; i<99; i++) if(i != 93 && i != 94) lp.setLed(i, (ex.indexOf(i+offset) == -1)?i+offset:0);
	}
	
	private void moveOffset(int chg) {
		offset += chg; if(offset < 0) offset = 0; if(offset > 29) offset = 29;
		System.out.println("[ColorTable] Offset: "+offset); drawColors();
	}
	
	protected void finalize() throws Throwable {
		lp.setListener(null); lp.setAll(-1);
		lp.flush(); lp.close(); lp = null;
	}
}

//SmoothDisplay disp = new SmoothDisplay(10, 10);
//SmoothOval oval = new SmoothOval(5f, 5f, 4f, 2.5f, LaunchPad.rgb(255, 255, 0));
//SmoothOval ovalB = new SmoothOval(5f, 5f, 1.8f, 1.8f, LaunchPad.rgb(0, 127, 0));
//SmoothRect rectBg = new SmoothRect(0f, 0f, 10f, 10f, LaunchPad.rgb(16, 32, 16));
//SmoothRotatedRect rect = new SmoothRotatedRect(5f, 5f, 6.5f, 3.8f, -180, LaunchPad.rgb(0, 127, 255));
//SmoothRotatedRect rect = SmoothRotatedRect.fromCoordinates(2.5f, 2.5f, 7.5f, 7.5f, -180, LaunchPad.rgb(127, 0, 64));
//SmoothLine line = new SmoothLine(1, 2.5f, 9, 7.4f, 0.8f, LaunchPad.rgb(127, 127, 0));
//disp.addShape(rect); disp.addShape(line); disp.addShape(rect); disp.addShapeAt(0, oval); disp.addShapeAt(0, rectB);
/*while(true) {
	float angle = rect.angle()+1.2f;
	rect.setPosition(rect.x(), rect.y(), rect.width(), rect.height(), angle);
	disp.update(); disp.redraw((x,y,color) -> { lp.setLedRgb(new Position(x, y).toLed(), color); });
	if(angle >= 180) break;
	try { Thread.sleep(30); } catch(InterruptedException e) { e.printStackTrace(); }
}*/