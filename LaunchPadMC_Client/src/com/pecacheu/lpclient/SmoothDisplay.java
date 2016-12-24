package com.pecacheu.lpclient;

import java.awt.AWTException;
import java.awt.Robot;

//This library generates anti-aliased geometry and gradients using 4x4 super-sampling.

public class SmoothDisplay {
	private ChuList<SmoothShape> geom = new ChuList<SmoothShape>(); //Shapes later in array are on top.
	private int[][] samples; private int level, xSize, ySize;
	
	public SmoothDisplay(int width, int height) {
		this.xSize = width; this.ySize = height; this.level = 4;
	}
	
	public SmoothDisplay(int width, int height, int level) {
		if(level < 1) level = 1;
		this.xSize = width; this.ySize = height; this.level = level;
	}
	
	//Geometry Management:
	
	public void addShape(SmoothShape shape) {
		geom.add(shape);
	}
	
	public boolean addShapeAt(int index, SmoothShape shape) {
		if(index <= geom.length) { geom.add(index, shape); return true; }
		return false;
	}
	
	public int indexOf(SmoothShape shape) {
		return geom.indexOf(shape);
	}
	
	public SmoothShape remove(int index) {
		return geom.remove(index);
	}
	public boolean remove(SmoothShape shape) {
		return geom.remove(shape);
	}
	
	//Display Updating & Drawing:
	
	public void update() {
		int xL=(xSize*level)+1, yL=(ySize*level)+1; samples = new int[xL][];
		for(int subX=0; subX<xL; subX++) { samples[subX] = new int[yL]; for(int subY=0; subY<yL; subY++) {
			for(int i=geom.length-1; i>=0; i--) { SmoothShape shape = geom.get(i);
				int color = shape.colorAt(subX/(float)level, subY/(float)level);
				if(color != -1) { samples[subX][subY] = color; break; } //TODO Alpha support?
			}
		}}
	}
	
	public int colorAt(int x, int y) {
		int sumR = 0, sumG = 0, sumB = 0, count = 0; //Take average of points near pixel:
		for(int subX=x*level,xL=subX+level; subX<=xL; subX++) for(int subY=y*level,yL=subY+level; subY<=yL; subY++) {
			int color = samples[subX][subY]; count++;
			sumR += (color & 0xFF); sumG += ((color & 0xFF00) >> 8); sumB += ((color & 0xFF0000) >> 16);
		} //Calculate mean average for each color:
		return LaunchPad.rgb(Math.round(sumR/(float)count), Math.round(sumG/(float)count), Math.round(sumB/(float)count));
	}
	
	public void redraw(Draw2D output) {
		for(int x=0; x<xSize; x++) for(int y=0; y<
		ySize; y++) output.draw(x, y, colorAt(x, y));
	}
}

@FunctionalInterface
interface Draw2D {
	public void draw(Integer x, Integer y, Integer color);
}

abstract class SmoothShape {
	public abstract int colorAt(float x, float y); //Get color at position, or -1 if there's no overlap.
	public abstract float x(); //Get shape's X position.
	public abstract float y(); //Get shape's Y position.
	public abstract float width(); //Get shape's width.
	public abstract float height(); //Get shape's height.
}

//------------------------------------------------------ SmoothRect ------------------------------------------------------

class SmoothRect extends SmoothShape {
	private float xMin, yMin, xMax, yMax; private int color;
	
	public SmoothRect(float xMin, float yMin, float xMax, float yMax, int color) {
		if(xMin > xMax) { float tmp = xMax; xMax = xMin; xMin = tmp; } //Switch MAX/MIN X
		if(yMin > yMax) { float tmp = yMax; yMax = yMin; yMin = tmp; } //Switch MAX/MIN Y
		this.xMin = xMin; this.yMin = yMin; this.xMax = xMax; this.yMax = yMax; this.color = color;
	}
	
	public static SmoothRect fromRelative(float x, float y, float width, float height, int color) {
		return new SmoothRect(x, y, x+width, y+height, color);
	}
	
	@Override
	public int colorAt(float x, float y) {
		return ((x > xMin && x < xMax) && (y > yMin && y < yMax))?color:-1; //TODO <= or just <?
	}
	
	public int color() { return color; }
	public void setColor(int color) { this.color = color; }
	
	//Get & Set Position:
	public float x() { return xMin; } public float xMax() { return xMax; }
	public float y() { return yMin; } public float yMax() { return yMax; }
	public float width() { return xMax-xMin; } public float height() { return yMax-yMin; }
	
	public void setPosition(float xMin, float yMin, float xMax, float yMax) {
		if(xMin > xMax) { float tmp = xMax; xMax = xMin; xMin = tmp; } //Switch MAX/MIN X
		if(yMin > yMax) { float tmp = yMax; yMax = yMin; yMin = tmp; } //Switch MAX/MIN Y
		this.xMin = xMin; this.yMin = yMin; this.xMax = xMax; this.yMax = yMax;
	}
}

//------------------------------------------------------ SmoothOval ------------------------------------------------------

class SmoothOval extends SmoothShape {
	private float x, y, xRadius, yRadius; private int color;
	
	//Calculated Values:
	private float xPow, yPow;
	
	public SmoothOval(float x, float y, float xRadius, float yRadius, int color) {
		this.x = x; this.y = y; this.xRadius = xRadius; this.yRadius = yRadius; this.color = color;
		xPow = (float)Math.pow(xRadius,2); yPow = (float)Math.pow(yRadius,2);
	}
	
	public static SmoothOval fromCoordinates(float xMin, float yMin, float xMax, float yMax, int color) {
		return new SmoothOval((xMax/2)+(xMin/2), (yMax/2)+(yMin/2), (xMax-xMin)/2, (yMax-yMin)/2, color);
	}
	
	@Override
	public int colorAt(float x, float y) {
		return ((Math.pow(x-this.x,2)/xPow) + (Math.pow(y-this.y,2)/yPow) < 0.8)?color:-1;
		//return (x > xMin && x < xMax) && (y > yMin && y < yMax);
	}
	//The ellipse formula, (x^2/rX^2) + (y^2/rY^2), will be > 1 for points outside oval and <= 1 inside oval!
	//This could also help if we ever add gradient shapes.
	
	public int color() { return color; }
	public void setColor(int color) { this.color = color; }
	
	//Get & Set Position:
	public float x() { return x; } public float xRadius() { return xRadius; }
	public float y() { return y; } public float yRadius() { return yRadius; }
	public float width() { return xRadius*2; } public float height() { return yRadius*2; }
	
	public void setPosition(float x, float y, float xRadius, float yRadius) {
		this.x = x; this.y = y; this.xRadius = xRadius; this.yRadius = yRadius;
		xPow = (float)Math.pow(xRadius,2); yPow = (float)Math.pow(yRadius,2);
	}
}

//------------------------------------------------------ SmoothLine ------------------------------------------------------

class SmoothLine extends SmoothShape {
	private float x1, y1, x2, y2, hThick;
	private int color;
	
	//Calculated Values:
	private float angle, length, aSin, aCos;
	
	public SmoothLine(float x1, float y1, float x2, float y2, float thickness, int color) {
		if(x1 > x2 || y1 > y2) { float tmp = x2; x2 = x1; x1 =
		tmp; tmp = y2; y2 = y1; y1 = tmp; } //Switch MAX/MIN
		this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
		this.hThick = thickness/2; this.color = color;
		this.angle = -(float)Math.atan((y2-y1)/(x2-x1));
		this.length = (float)Math.sqrt(Math.pow(x2-x1,2) + Math.pow(y2-y1,2));
		aSin = (float)Math.sin(this.angle); aCos = (float)Math.cos(this.angle);
	}
	
	public static SmoothLine fromRelative(float x, float y, float length, float angle, float thickness, int color) {
		
		return null;//return new SmoothLine(x, y, x+width, y+height, color);
	}
	
	@Override
	public int colorAt(float x, float y) {
		//x=(A-x)\cdot \cos \left(-z\right)-(B-y)\cdot \sin \left(-z\right)+x
		//y=(B-y)\cdot \cos \left(-z\right)+(A-x)\cdot \sin \left(-z\right)+y+\left(\frac{d}{2}\right)
		//x=(C-x)\cdot \cos \left(-z\right)-(D-y)\cdot \sin \left(-z\right)+x
		//y=(D-y)\cdot \cos \left(-z\right)+(C-x)\cdot \sin \left(-z\right)+y-\left(\frac{d}{2}\right)
		
		float relX = x-this.x1; //Position corrected X.
		float relY = y-this.y1; //Position corrected Y.
		
		float rotX = relX*aCos-relY*aSin; //Rotation corrected X.
		float rotY = relY*aCos+relX*aSin; //Rotation corrected Y.
		
		return ((rotX > 0 && rotX < length) && (rotY > -hThick && rotY < hThick))?color:-1;
	}
	
	public int color() { return color; }
	public void setColor(int color) { this.color = color; }
	
	//Get & Set Position:
	public float x() { return x1; } public float x2() { return x2; }
	public float y() { return y1; } public float y2() { return y2; }
	public float width() { return hThick*2; } public float height() { return length; }
	public float length() { return length; } public float angle() { return -(float)Math.toDegrees(angle); }
	
	public void setPosition(float x1, float y1, float x2, float y2, float thickness) {
		if(x1 > x2 || y1 > y2) { float tmp = x2; x2 = x1; x1 =
		tmp; tmp = y2; y2 = y1; y1 = tmp; } //Switch MAX/MIN
		this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.hThick = thickness/2;
		this.angle = -(float)Math.atan((y2-y1)/(x2-x1));
		this.length = (float)Math.sqrt(Math.pow(x2-x1,2) + Math.pow(y2-y1,2));
		aSin = (float)Math.sin(this.angle); aCos = (float)Math.cos(this.angle);
	}
}

//------------------------------------------------------ SmoothRotatedRect ------------------------------------------------------

class SmoothRotatedRect extends SmoothShape {
	private float x, y, hWidth, hHeight, angle;
	private int color;
	
	//Calculated Values:
	private float aSin, aCos;
	
	public SmoothRotatedRect(float x, float y, float width, float height, float angle, int color) {
		this.x = x; this.y = y; this.hWidth = width/2; this.hHeight = height/2;
		this.angle = (float)Math.toRadians(angle); this.color = color;
		aSin = (float)Math.sin(this.angle); aCos = (float)Math.cos(this.angle);
	}
	
	public static SmoothRotatedRect fromCoordinates(float x1, float y1, float x2, float y2, float angle, int color) {
		if(x1 > x2) { float tmp = x2; x2 = x1; x1 = tmp; } //Switch MAX/MIN X
		if(y1 > y2) { float tmp = y2; y2 = y1; y1 = tmp; } //Switch MAX/MIN Y
		float angleTmp = Math.abs(angle); if(angleTmp > 135) angleTmp -= 90; else if(angleTmp < 45) angleTmp += 90;
		float angleRad = (float)Math.toRadians(-angleTmp); float x = (x2/2)+(x1/2), y = (y2/2)+(y1/2);
		//Resize to keep rectangle within top/bottom bounds despite rotation:
		float sin = (float)Math.sin(angleRad), cos = (float)Math.cos(angleRad);
		float tlX = x - (y2-y1)/2 * cos - (y2-y1)/2 * sin; float tlY = y + (y2-y1)/2 * cos - (y2-y1)/2 * sin;
		float brX = x + (y2-y1)/2 * cos + (y2-y1)/2 * sin; float brY = y - (y2-y1)/2 * cos + (y2-y1)/2 * sin;
		float size = (tlY-brY)/2 + (tlX-brX)/2; return new SmoothRotatedRect(x, y, size, size, angle, color);
	}
	
	@Override
	public int colorAt(float x, float y) {
		//Line 1: x=(a-x)\cdot \cos \left(z\right)-(b-y)\cdot \sin \left(z\right)+x-\left(\frac{c}{2}\right)
		//Line 2: y=(b-y)\cdot \cos \left(z\right)+(a-x)\cdot \sin \left(z\right)+y-\left(\frac{d}{2}\right)
		//Line 3: x=(a-x)\cdot \cos \left(z\right)-(b-y)\cdot \sin \left(z\right)+x+\left(\frac{c}{2}\right)
		//Line 4: y=(b-y)\cdot \cos \left(z\right)+(a-x)\cdot \sin \left(z\right)+y+\left(\frac{d}{2}\right)
		/*UL  =  x + ( Width / 2 ) * cos -A - ( Height / 2 ) * sin -A ,  y + ( Height / 2 ) * cos -A  + ( Width / 2 ) * sin -A
		UR  =  x - ( Width / 2 ) * cos -A - ( Height / 2 ) * sin -A ,  y + ( Height / 2 ) * cos -A  - ( Width / 2 ) * sin -A
		BL =   x + ( Width / 2 ) * cos -A + ( Height / 2 ) * sin -A ,  y - ( Height / 2 ) * cos -A  + ( Width / 2 ) * sin -A
		BR  =  x - ( Width / 2 ) * cos -A + ( Height / 2 ) * sin -A ,  y - ( Height / 2 ) * cos -A  - ( Width / 2 ) * sin -A*/
		
		//K,L = TEST POINTS
		//A,B = BOX POSITION
		//I,J = POS CORRECT
		//H,G = ROTATION CORRECT
		
		//i=k-a, j=l-b
		float relX = x-this.x; //Position corrected X.
		float relY = y-this.y; //Position corrected Y.
		
		//h = i\cdot \cos \left(z\right)-j\cdot \sin \left(z\right)
		//g = j\cdot \cos \left(z\right)+i\cdot \sin \left(z\right)
		float rotX = relX*aCos-relY*aSin; //Rotation corrected X.
		float rotY = relY*aCos+relX*aSin; //Rotation corrected Y.
		
		return ((rotX > -hWidth && rotX < hWidth) && (rotY > -hHeight && rotY < hHeight))?color:-1;
	}
	
	public void setColor(int color) { this.color = color; }
	
	//Get & Set Position:
	public float x() { return x; } public float width() { return hWidth*2; }
	public float y() { return y; } public float height() { return hHeight*2; }
	public float angle() { return (float)Math.toDegrees(angle); }
	
	public void setPosition(float x, float y, float width, float height, float angle) {
		this.x = x; this.y = y; this.hWidth = width/2; this.hHeight = height/2;
		this.angle = (float)Math.toRadians(angle);
		aSin = (float)Math.sin(this.angle); aCos = (float)Math.cos(this.angle);
	}
}

//------------------------------------------------------ ScreenCapture ------------------------------------------------------

class ScreenCapture extends SmoothShape {
	private Robot scr; private java.awt.Rectangle scrRect;
	private java.awt.image.BufferedImage scrImage = null;
	
	//Calculated Values:
	private float scrMinX, scrMinY, scrMaxX, scrMaxY,
	posMinX, posMinY, posMaxX, posMaxY;
	
	public ScreenCapture(SmoothRect position, java.awt.Rectangle captureSize) throws AWTException {
		scrRect = captureSize; scr = new Robot();
		scrMinX = (float)scrRect.getMinX(); scrMaxX = (float)scrRect.getMaxX()-1;
		scrMinY = (float)scrRect.getMinY(); scrMaxY = (float)scrRect.getMaxY()-1;
		posMinX = position.x(); posMaxX = position.xMax();
		posMinY = position.y(); posMaxY = position.yMax();
	}
	public ScreenCapture(SmoothRect position, SmoothRect captureSize) throws AWTException {
		this(position, new java.awt.Rectangle((int)captureSize.x(), (int)
		captureSize.y(), (int)captureSize.width(), (int)captureSize.height()));
	}
	public ScreenCapture(SmoothRect position) throws AWTException {
		this(position, new java.awt.Rectangle(java.awt
		.Toolkit.getDefaultToolkit().getScreenSize()));
	}
	
	@Override
	public int colorAt(float x, float y) {
		//TODO Shouldn't rely on LaunchPad library, should we?
		if(x < posMinX || x > posMaxX || y < posMinY || y > posMaxY) return -1;
		int relX = (int)LaunchPad.map(x, posMinX, posMaxX, scrMinX+2, scrMaxX-1);
		int relY = (int)LaunchPad.map(y, posMinY, posMaxY, scrMaxY-1, scrMinY+1);
		//Color clr = scr.getPixelColor(relX, relY); return LaunchPad.rgb(clr.getRed(), clr.getGreen(), clr.getBlue());
		try { if(scrImage != null) return LaunchPad.fromBGR(scrImage.getRGB(relX, relY)); }
		catch(Exception e) { System.out.println("Fatal ScreenBuffer Read Error!"); System.exit(0); }
		return -1;
	}
	
	public void capture() { scrImage = scr.createScreenCapture(new java.awt
	.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize())); }
	
	//Get & Set Position:
	public float x() { return posMinX; } public float y() { return posMinY; }
	public float width() { return posMaxX-posMinX; } public float height() { return posMaxY-posMinY; }
	
	//Screen Capture Position:
	public float capX() { return scrRect.x; } public float capY() { return scrRect.y; }
	public float capWidth() { return scrRect.width; } public float capHeight() { return scrRect.height; }
	
	public void setPosition(SmoothRect position) {
		posMinX = position.x(); posMinX = position.xMax();
		posMinY = position.y(); posMaxY = position.yMax();
	}
	
	public void setCaptureSize(java.awt.Rectangle captureSize) {
		scrRect = captureSize; scrMinX = (float)scrRect.getMinX(); scrMaxX = (float)scrRect
		.getMaxX()-1; scrMinY = (float)scrRect.getMinY(); scrMaxY = (float)scrRect.getMaxY()-1;
	}
	public void setCaptureSize(SmoothRect captureSize) {
		setCaptureSize(new java.awt.Rectangle((int)captureSize.x(), (int)
		captureSize.y(), (int)captureSize.width(), (int)captureSize.height()));
	}
}