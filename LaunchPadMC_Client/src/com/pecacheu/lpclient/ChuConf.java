//ChuConf Config File Interpreter, 2016 Pecacheu. GNU GPL v3

package com.pecacheu.lpclient;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.regex.Pattern;

public class ChuConf {
	public static final String EXT = ".cnf";
	public static final Pattern KEY_CHECK = Pattern.compile("[^a-zA-Z0-9_]");
	private static final Pattern KEY_TEST = Pattern.compile("^[a-zA-Z]+[a-zA-Z0-9_]*$"),
	VAL_TEST = Pattern.compile("^[ -9;-~]+$"), REM_NEWLINE = Pattern.compile("[\n\r]");
	
	private TreeMap<String,Object> prop = new TreeMap<String,Object>();
	String header = "";
	
	ChuConf() {}
	ChuConf(String raw) throws Exception { parse(raw); }
	
	//---- Convenience Functions:
	
	public static boolean confExists(String fname) {
		File file = new File(fname+EXT);
		return file.exists() && !file.isDirectory();
	}
	
	public static String jarDir() {
		String dir = new File(ClassLoader.getSystemClassLoader().getResource(".").getPath()).getPath();
		return dir+File.separator;
	}
	
	//---- File Loading:
	
	//Must include extension in file name!
	public static String unpack(String intFile, String outFile) throws Exception {
		String data = readFile(ChuConf.class.getResourceAsStream(intFile));
		PrintWriter writer = new PrintWriter(outFile==null?intFile:outFile, "UTF-8");
		writer.print(data); writer.close(); return data;
	}
	
	public static ChuConf loadUnpack(String iname) throws Exception {
		return new ChuConf(readFile(ChuConf.class.getResourceAsStream(iname+EXT)));
	}
	
	public static ChuConf load(String fname) throws Exception {
		return new ChuConf(readFile(new FileInputStream(fname+EXT)));
	}
	
	private static String readFile(InputStream input) throws Exception {
		String data = ""; while(input.available() > 0) {
			int c=input.read(); if(c<0) break; data += (char)c;
		}
		input.close(); return data;
	}
	
	public void save(String fname) throws Exception {
		PrintWriter writer = new PrintWriter(fname+EXT, "UTF-8");
		writer.print(this); writer.close();
	}
	
	//---- String Parsing:
	
	public void parse(String data) throws Exception {
		ChuList<String> lines = new ChuList<String>(data.split("\n"));
		ChuIterator<String> it = lines.chuIterator();
		
		while(it.hasNext()) {
			//Read next line:
			String line = it.next();
			if(line.indexOf('#') != -1) {
				if(it.index==0) { header = line.substring(line.indexOf('#')+1); }
				line = line.substring(0, line.indexOf('#'));
			}
			line = line.trim();
			
			//Parse line:
			if(line.length() <= 0) continue; int scInd = line.indexOf(':');
			if(scInd == line.length()-1) parseSub(line.substring(0,line.length()-1), 1, it, null); //Section
			else if(scInd != -1) trySetProp(line.substring(0,scInd), line.substring(scInd+1).trim(), it.index+1); //Property
			else throw new Exception("Expected semicolon! Line: "+(it.index+1));
		}
	}
	
	private void parseSub(String name, int depth, ChuIterator<String> it, ChuConfSection par) throws Exception {
		//TODO Main.dbg("parseSub, depth="+depth+", name="+name);
		if(!isValid(name)) throw new Exception("Invalid section name! Line: "+(it.index+1));
		
		ChuConfSection sec = new ChuConfSection();
		
		boolean fLine = true; while(it.hasNext()) {
			//Read next line:
			String line = it.next();
			if(line.indexOf('#') != -1) line = line.substring(0, line.indexOf('#'));
			line = line.trim();
			
			//Check indent:
			if(line.length() <= 0) continue;
			int d = 0; for(int i=0,l=line.length(); i<l; i++) { if(line.charAt(i) != '-') break; d++; }
			if(d < depth) { if(fLine) throw new Exception("Section has no content! Line: "+(it.index+1)); else { it.goBack(); break; }}
			else if(d > depth) throw new Exception("Found too much indent! Line: "+(it.index+1));
			if(line.charAt(d) != ' ') throw new Exception("Expected space after indent! Line: "+(it.index+1));
			line = line.substring(d+1);
			
			//Parse line:
			int scInd = line.indexOf(':');
			if(scInd == line.length()-1) parseSub(line.substring(0,line.length()-1), depth+1, it, sec); //Section
			else if(scInd != -1) sec.trySetProp(line.substring(0,scInd), line.substring(scInd+1).trim(), it.index+1); //Property
			else sec.tryAddProp(line, it.index+1); //Keyless Property
			fLine = false;
		}
		if(par == null) setSection(name, sec); else par.setSection(name, sec);
	}
	
	private void trySetProp(String key, String rawVal, int line) throws Exception {
		//TODO Main.dbg("trySetProp:"+key+","+rawVal);
		if(!isValid(key)) throw new Exception("Invalid property key '"+key+"'! Line: "+line);
		try {
			if(rawVal.contains(",")) { //List:
				String[] vals = rawVal.split(","); ChuList<Object> valList = new ChuList<Object>();
				//Determine list type:
				int type = 0; //List Of Integer
				if(vals[0].contains(".")) type = 1; //List Of Double
				for(int i=0,l=vals.length; i<l; i++) {
					if(type==1) valList.add(new Double(vals[i]));
					else valList.add(new Integer(vals[i]));
				}
				setProp(key, valList);
			} else if(rawVal.contains(".")) setProp(key, new Double(rawVal)); //Double
			else setProp(key, new Integer(rawVal)); //Integer
		} catch(NumberFormatException e) {
			if(!setProp(key, rawVal)) throw new Exception("Invalid line formatting! Line: "+line); //String
		}
		//TODO Main.dbg("RESULT: "+getProp(key));
	}
	
	@Override
	public String toString() {
		String data = ""; Iterator<String> it = prop.keySet().iterator();
		if(header.length() > 0) data += "#"+header+"\n\n";
		while(it.hasNext()) { String key = it.next(); data += propToStr(key, prop.get(key), 1); }
		return data.length()==0?"":data.substring(0, data.length()-1); //Remove last newline.
	}
	
	static String propToStr(String key, Object prop, int depth) {
		if(prop instanceof ChuConfSection) return key+":\n"+((ChuConfSection)prop).toString(depth)+"\n"; //Section
		return key+": "+propToStr(prop); //Other Property
	}
	static String propToStr(Object prop) {
		if(prop instanceof ChuList<?>) return ((ChuList<?>)prop).join(",")+"\n"; //Array
		return prop.toString()+"\n"; //Other Property
	}
	
	//---- Config Reading / Writing:
	static boolean isValid(String k) { return KEY_TEST.matcher(k).matches(); }
	static boolean isValid(String k, String v) { return KEY_TEST.matcher(k).matches()&&VAL_TEST.matcher(v).matches(); }
	
	public boolean setProp(String n, String v) { if(!isValid(n,v)) return false; prop.put(n, v); return true; }
	public boolean setProp(String n, Integer v) { if(!isValid(n)) return false; prop.put(n, v); return true; }
	public boolean setProp(String n, Double v) { if(!isValid(n)) return false; prop.put(n, v); return true; }
	public boolean setProp(String n, ChuList<Object> v) { if(!isValid(n)) return false; prop.put(n, v); return true; }
	public boolean setSection(String n, ChuConfSection s) { if(!isValid(n)) return false; prop.put(n, s); return true; }
	
	public Object getProp(String name) { return prop.get(name); }
	public Iterator<String> getPropKeys() { return prop.keySet().iterator(); }
	
	public void setHeader(String h) {
		if(h != null) header = REM_NEWLINE.matcher(h).replaceAll("");
	}
}

class ChuConfSection {
	private TreeMap<String,Object> prop = new TreeMap<String,Object>();
	private ChuList<Object> list = new ChuList<Object>();
	
	ChuConfSection() {}
	
	void trySetProp(String key, String rawVal, int line) throws Exception {
		//TODO Main.dbg("subsection trySetProp:"+key+","+rawVal);
		if(!ChuConf.isValid(key)) throw new Exception("Invalid property key '"+key+"'! Line: "+line);
		try {
			if(rawVal.contains(",")) { //List:
				String[] vals = rawVal.split(","); ChuList<Object> valList = new ChuList<Object>();
				//Determine list type:
				int type = 0; //List Of Integer
				if(vals[0].contains(".")) type = 1; //List Of Double
				for(int i=0,l=vals.length; i<l; i++) {
					if(type==1) valList.add(new Double(vals[i]));
					else valList.add(new Integer(vals[i]));
				}
				setProp(key, valList);
			} else if(rawVal.contains(".")) setProp(key, new Double(rawVal)); //Double
			else setProp(key, new Integer(rawVal)); //Integer
		} catch(NumberFormatException e) {
			if(!setProp(key, rawVal)) throw new Exception("Invalid line formatting! Line: "+line); //String
		}
		//TODO Main.dbg("RESULT: "+getProp(key));
	}
	void tryAddProp(String rawVal, int line) throws Exception {
		//TODO Main.dbg("subsection tryAddProp:"+rawVal);
		try {
			if(rawVal.contains(",")) { //List:
				String[] vals = rawVal.split(","); ChuList<Object> valList = new ChuList<Object>();
				//Determine list type:
				int type = 0; //List Of Integer
				if(vals[0].contains(".")) type = 1; //List Of Double
				for(int i=0,l=vals.length; i<l; i++) {
					if(type==1) valList.add(new Double(vals[i]));
					else valList.add(new Integer(vals[i]));
				}
				addProp(valList);
			} else if(rawVal.contains(".")) addProp(new Double(rawVal)); //Double
			else addProp(new Integer(rawVal)); //Integer
		} catch(NumberFormatException e) {
			if(!addProp(rawVal)) throw new Exception("Invalid line formatting! Line: "+line); //String
		}
		//TODO Main.dbg("RESULT: "+getPropAt(list.size()-1));
	}
	
	public boolean setProp(String n, String v) { if(!ChuConf.isValid(n,v)) return false; prop.put(n, v); return true; }
	public boolean setProp(String n, Integer v) { if(!ChuConf.isValid(n)) return false; prop.put(n, v); return true; }
	public boolean setProp(String n, Double v) { if(!ChuConf.isValid(n)) return false; prop.put(n, v); return true; }
	public boolean setProp(String n, ChuList<Object> v) { if(!ChuConf.isValid(n)) return false; prop.put(n, v); return true; }
	public boolean setSection(String n, ChuConfSection s) { if(!ChuConf.isValid(n)) return false; prop.put(n, s); return true; }
	
	public boolean addProp(String v) { if(!ChuConf.isValid("a",v)) return false; list.add(v); return true; }
	public void addProp(Integer v) { list.add(v.toString()); }
	public void addProp(Double v) { list.add(v.toString()); }
	public void addProp(ChuList<Object> v) { list.add(v); }
	
	public Object getProp(String name) { return prop.get(name); }
	public Object getPropAt(int ind) { return list.get(ind); }
	public Iterator<String> getPropKeys() { return prop.keySet().iterator(); }
	public ChuIterator<Object> getPropList() { return list.chuIterator(); }
	
	@Override
	public String toString() { return toString(1); }
	
	public String toString(int depth) {
		String str="",dStr="-"; for(int i=1; i<depth; i++) dStr += "-"; Iterator<String> ki = getPropKeys();
		while(ki.hasNext()) { String key = ki.next(); str += dStr+" "+ChuConf.propToStr(key, prop.get(key), depth+1); }
		ChuIterator<Object> li = getPropList(); while(li.hasNext()) str += dStr+" "+ChuConf.propToStr(li.next());
		return str.length()==0?"":str.substring(0, str.length()-1); //Remove last newline.
	}
}