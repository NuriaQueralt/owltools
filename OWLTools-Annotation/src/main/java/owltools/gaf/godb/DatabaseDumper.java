package owltools.gaf.godb;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;

import owltools.gaf.GafDocument;
import owltools.graph.OWLGraphWrapper;

/**
 * Generates a relational database dump.
 * 
 * Currently there is one subclass, for the GO MySQL "lead" database;
 * in principle this is easily extended, e.g. for Chado
 * 
 * Not intended for incremental updates; bulk loading only
 * 
 * @author cjm
 *
 */
public abstract class DatabaseDumper {

	protected OWLGraphWrapper graph;
	protected Map<Object,Integer> objIdMap = new HashMap<Object,Integer>();
	protected Map<String,Integer> objLastIdMap = new HashMap<String,Integer>();
	protected Set<String> incrementallyLoadedTables = new HashSet<String>(); 
	protected String targetDirectory = "target";
	
	Set<GafDocument> gafdocs = new HashSet<GafDocument>();


	public String getTargetDirectory() {
		return targetDirectory;
	}


	public void setTargetDirectory(String targetDirectory) {
		this.targetDirectory = targetDirectory;
	}

	public void addGafDocument(GafDocument gd) {
		gafdocs.add(gd);
	}
	
	

	public Set<GafDocument> getGafdocs() {
		return gafdocs;
	}

	public void setGafdocs(Set<GafDocument> gafdocs) {
		this.gafdocs = gafdocs;
	}
	/**
	 * dumps all tables
	 * @throws IOException 
	 * @throws ReferentialIntegrityException 
	 * 
	 */
	public abstract void dump() throws IOException, ReferentialIntegrityException;
	

	protected void dumpRow(PrintStream termStream, Object... vals) {
		int n = 0;
		for (Object v : vals) {
			if (n > 0)
				termStream.print("\t");
			termStream.print(v);
			n++;
		}
		termStream.print("\n");

	}

	protected Integer getId(String table, Object obj) throws ReferentialIntegrityException {
		return getId(table, obj, false);
	}
	protected Integer getId(String table, Object obj, boolean isForceExists) throws ReferentialIntegrityException {
		if (objIdMap.containsKey(obj)) {
			return objIdMap.get(obj);
		}
		if (isForceExists) {
			throw new ReferentialIntegrityException(table, obj);
		}
		if (!objLastIdMap.containsKey(table)) {
			objLastIdMap.put(table, 0);
		}
		int id = objLastIdMap.get(table) + 1;
		objLastIdMap.put(table, id);
		objIdMap.put(obj, id);
		return id;

	}
	
	protected PrintStream getPrintStream(String t) throws IOException {
		FileOutputStream fos;
		FileUtils.forceMkdir(new File(targetDirectory));
		String path = targetDirectory + "/" + t.toString() + ".txt";
		if (this.incrementallyLoadedTables.contains(t)) {
			// TODO - allow option to introspect file for lastId
			fos = new FileOutputStream(path, true);
			
		}
		else {
			fos = new FileOutputStream(path);
		}
		return new PrintStream(new BufferedOutputStream(fos));

	}

}
