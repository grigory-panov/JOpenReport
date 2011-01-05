package org.jopenreport;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.log4j.Logger;
import org.apache.xml.serialize.OutputFormat;
import org.apache.xml.serialize.XMLSerializer;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import org.xml.sax.SAXException;


/**
 * @author Grigory Panov <grigory.panov@gmail.com>
 *         Date: 30.12.2010
 */

public class ReportGenerator {
	public final static String PARAM="param";
	private static Logger logger = Logger.getLogger(ReportGenerator.class);
	private Document docTpl = null;
	private Document docStyle = null;
	private HashMap<String, String> values = new HashMap<String, String>();
	private final static String open_token = "<:";
	private final static String close_token = ":>";
	private final static String open_token_section = "[:";
	private final static String close_token_section = ":]";
	private String templateName = null;
	private final static int BUFFER = 2048;

	
	/**
	 * Opens template
	 * @param fname template name
	 * @return false, if file cannot be opened or parsed, true, if all right
	 */
	public boolean open(String fname)  {
		ZipFile zipFile = null;
		templateName = fname;
		try {
			zipFile = new ZipFile(fname);
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			for (Enumeration e = zipFile.entries(); e.hasMoreElements();){
				ZipEntry zipEntry = (ZipEntry) e.nextElement();
				logger.debug("in zip file: " + zipEntry.getName());
				if(zipEntry.getName().equals("content.xml")){
					logger.debug("load content...");
					docTpl = dBuilder.parse(zipFile.getInputStream(zipEntry));

				}else if(zipEntry.getName().equals("styles.xml")){
					logger.debug("load styles...");
					docStyle = dBuilder.parse(zipFile.getInputStream(zipEntry));
				}
			}
		} catch (IOException e) { 
			return false;
		} catch (ParserConfigurationException e){
			return false;
		} catch (SAXException e) {
			return false;
		}finally{
			if(zipFile != null){
				try{ zipFile.close(); } catch(IOException e) {}
			}
		}
		return true;
	}

	/**
	 * Closes template file, clears all temporary resources
	 */
	public void close(){
		values.clear();
		docTpl = null;
		docStyle = null;
	}

	/**
	 * Clears tag values, settled by {@link setValue}
	 */
	public void clear(){
		values.clear();
	}

	
	/**
	 * Function for get tag value
	 * @param name tag name
	 * @return tag value
	 */
	public String getValue( String name )
	{
		if(values.containsKey(name)){
			return values.get(name);
		}
		else{
			logger.debug("value for field " + name + " not setted");
			return "";
		}
	}

	/**
	 * Function for set tag values in report.
	 * @param name tag name. For "fields" it should be "param", for "sections" - tag name (content between <::>)
	 * @param value value for substitution. 
	 */
	public void setValue( String name, String value ){
		values.put(name, value);
	}


	/**
	 * Exists two different type of tags: "fields" and "sections". 
	 * Function does different actions for different types of tags, 
	 * for fields - just add value to tag
	 * for sections - copy row of section and replace all tags in new row by values.
	 * For setting values use function {@link setValue} 
	 * You can execute this function how many times how you needed, values, settled by previous calls 
	 * setValue will be applied. 
	 * 
	 * @param sname tag name
	 */
	public void exec( String sname )
	{
		logger.debug("exec " + sname);
		setValue(sname, getValue(PARAM));
		//search tags in content
		Node n = docTpl.getLastChild();
		while( n != null ){
			searchTags(n, sname);
			n = n.getPreviousSibling();
		}
		//search tags in colontitles
		n = docStyle.getLastChild();
		while( n != null ){
			searchTags(n, sname);
			n = n.getPreviousSibling();
		}

	}

	/**
	 * Function for search and replace tags by values in given node.
	 * Do not deletes tags, just add value to end of tag. For deletion use function cleanUpTags()
	 * @param node node from which search begins
	 * @param sname tag name
	 */
	private void searchTags( Node node, String sname )
	{
		Node n = node.getLastChild();
		while( n != null ){
			boolean res = getNodeTags(n, sname, false);
			if( res ) {
				insertRowValues(n);
			}else{
				res = getNodeTags(n, sname, true);
				if(res)
					insertTagsValues(n, sname);
				else
					searchTags(n, sname);
			}
			n = n.getPreviousSibling();
		}
	}


	/**
	 * Search tag by name
	 * @param node node from which search started
	 * @param tagname tag name
	 * @param params true, if looking for field tag, false if section
	 * @return true, if given tag found
	 */
	private boolean getNodeTags(Node node, String tagname, boolean params )
	{
		if(node.getNodeType() == Node.TEXT_NODE){
			String str = node.getNodeValue();
			if(params){
				return str.indexOf(open_token + tagname + close_token) != -1;
			} else{
				return str.indexOf(open_token_section + tagname + close_token_section) != -1;
			}
		}
		return false;  
	}

	/** Function inserts new row, replaces tags by values and remove section tag from new row.
	 * @param node
	 */
	private void insertRowValues(Node node)
	{
		logger.debug("insert row values for node" + node.getTextContent());
		Node n = node;
		while(n.getParentNode() != null){
			n = n.getParentNode();
			if( n.getNodeName()=="table:table-row" ){
				logger.debug("clear tags for " + n.getTextContent());
				Node newNode = n.cloneNode(true);
				n.getParentNode().insertBefore(newNode, null);
				clearTags(n,true);
				logger.debug("insert new row before");
				Iterator<String> it;
				for ( it = values.keySet().iterator(); it.hasNext(); ) {
					logger.debug("set values " + it);
					searchTags(n, it.next());
				}

			}
		}
	}

	/** Append tag value to field tag. Tag not disappear, so you call this function many times for one tag.
	 * @param node node with tag text
	 * @param tagName tag name
	 */
	private void insertTagsValues(Node node, String tagName){
		logger.debug("tag name = " + tagName + ", inserted");
		Node n = node; 
		n.setNodeValue(n.getNodeValue()+getValue(tagName));
	}
	
	/**
	 * Function removes all tags from report. Call this function before saving. 
	 */
	public void cleanUpTags(){
		//clear tags in content
		Node n = docTpl.getLastChild();
		while( n !=null ){
			clearTags(n,false);
			n = n.getPreviousSibling();
		}
		n = docTpl.getLastChild();
		while( n != null ) {
			clearRow(n);
			n = n.getPreviousSibling();
		}
		//clear tags in colontituls
		n = docStyle.getLastChild();
		while( n != null ) {
			clearTags(n,false);
			n = n.getPreviousSibling();
		}
		n = docStyle.getLastChild();
		while( n != null ) {
			clearRow(n);
			n = n.getPreviousSibling();
		}
	}

	/** Function removes any tags from node
	 * @param node 
	 * @param section true for section, false for fields
	 */
	private void clearTags(Node node, boolean section )
	{
		if(node == null) return;

		Node n = node.getLastChild();
		while( n != null){
			if(n.getNodeType() == Node.TEXT_NODE){
				//logger.debug("clear tags for node " + node.getTextContent());
				String str = n.getNodeValue();
				if(section){
					//logger.debug("before removing section tag " + str) ;
					str = str.replaceFirst(Pattern.quote(open_token_section) + ".*" + Pattern.quote(close_token_section), "");
					//logger.debug("after removing section tag " + str);
				}else {
					//logger.debug("before removing tag " + str) ;
					str = str.replaceFirst(Pattern.quote(open_token) + ".*" + Pattern.quote(close_token), "");
					//logger.debug("after removing tag " + str);
				}
				n.setNodeValue(str);	
			}else{
				clearTags(n,section);
			}
			n = n.getPreviousSibling();
		}

	}
	
	/** function removes rows with section tag from node
	 * @param node
	 */
	private void clearRow(Node node)
	{

		Node n = node.getLastChild();
		while( n != null)
		{	
			if(n.getNodeType() == Node.TEXT_NODE){
				//logger.debug("clear row " + node.getTextContent());
				String str = n.getNodeValue();
				if(Pattern.matches(Pattern.quote(open_token_section) + ".*" + Pattern.quote(close_token_section), str)){
					Node tmp = n;
					while(tmp.getParentNode()!= null){
						tmp = tmp.getParentNode();
						if( tmp.getNodeName()=="table:table-row" ){
							tmp.getParentNode().removeChild(tmp);
							break;
						}
					}
				}
			}else{
				clearRow(n);
			}
			n = n.getPreviousSibling();
		}

	}
	
	/** Function saves report with given name. Before uses call cleanUpTags()
	 * @param fname name for saving
	 * @return true, if report was saved, false otherwise
	 */
	public boolean save( String fname ){
		if(docTpl!= null ){
			File template = new File(templateName);
			File out = new File(fname);
			try {
				XMLSerializer serializer = new XMLSerializer();
				OutputFormat of = new OutputFormat();
				of.setEncoding("UTF-8");
				serializer.setOutputByteStream(new FileOutputStream("content.xml"));
				serializer.setOutputFormat(of);
				serializer.serialize(docTpl);
				serializer.setOutputByteStream(new FileOutputStream("styles.xml"));
				serializer.setOutputFormat(of);
				serializer.serialize(docStyle);
				File content = new File("content.xml");
				File styles = new File("styles.xml");
				addFilesToExistingZip(template, out, new File[] {content, styles} );
				content.delete();
				styles.delete();
			} catch (IOException e) {
				logger.debug("error!");
				return false;
			}
		}else{
			return false;
		}
		return true;
	}

	private static void addFilesToExistingZip(File zipFile, File destZipFile, File[] files) throws IOException {
		destZipFile.delete();
		byte[] buf = new byte[BUFFER];

		ZipInputStream zin = new ZipInputStream(new FileInputStream(zipFile));
		ZipOutputStream out = new ZipOutputStream(new FileOutputStream(destZipFile));

		ZipEntry entry = zin.getNextEntry();
		while (entry != null) {
			String name = entry.getName();
			boolean notInFiles = true;
			for (File f : files) {
				if (f.getName().equals(name)) {
					notInFiles = false;
					break;
				}
			}
			if (notInFiles) {
				// Add ZIP entry to output stream.
				out.putNextEntry(new ZipEntry(name));
				// Transfer bytes from the ZIP file to the output file
				int len;
				while ((len = zin.read(buf)) > 0) {
					out.write(buf, 0, len);
				}
			}
			entry = zin.getNextEntry();
		}
		// Close the streams            
		zin.close();
		// Compress the files
		for (int i = 0; i < files.length; i++) {
			InputStream in = new FileInputStream(files[i]);
			// Add ZIP entry to output stream.
			out.putNextEntry(new ZipEntry(files[i].getName()));
			// Transfer bytes from the file to the ZIP file
			int len;
			while ((len = in.read(buf)) > 0) {
				out.write(buf, 0, len);
			}
			// Complete the entry
			out.closeEntry();
			in.close();
		}
		// Complete the ZIP file
		out.close();
		//tempFile.delete();
	}

}


