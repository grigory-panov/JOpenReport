package com.rstyle.rtn.license.business;


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
    private final static String PARAM="param";
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
    
    public void close(){
        values.clear();
        docTpl = null;
        docStyle = null;
    }
    
    public void clear(){
        values.clear();
    }
    
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
    
    public void setValue( String name, String value ){
            values.put(name, value);
    }
    
     /*
     * Выполняет подстановку значения параметра в шаблоне.
     * Есть 2 типа тегов 
     *  \arg обычные теги 
     *  \arg секции - могут находиться ТОЛЬКО в строках таблицы. 
     * Для подстановки значений обычных тегов необходимо выполнить setValue() где name = PARAM (сейчас #define PARAM "param") а value - значение для подстановки. Потом выполнить exec() с параметром = имени тега.
     * Для подстановки секций необходимо задать нужные параметры, используя setValue()
     * а потом выполнить exec() с именем секции. 
     * exec может вызываться нужное число раз как для обычных тегов, так и для секций
     */
    public String exec( String sname )
    {
        logger.debug("exec " + sname);
        setValue(sname, getValue(PARAM));
        //search tags in content
        Node n = docTpl.getLastChild();
        while( n != null ){
                searchTags(n, sname);
                n = n.getPreviousSibling();
        }
        //search tags in colontituls
        n = docStyle.getLastChild();
        while( n != null ){
                searchTags(n, sname);
                n = n.getPreviousSibling();
        }
        return docTpl.toString();
        
    }
    /*
  *	Рекурсивная функция поиска и замены тегов на их значения в node.
 *	Не заменяет теги, а дописывает значения в конец. 
 *	Для удаления тэгов используйте функцию cleanUpTags()

     */
    void searchTags(Node node, String sname )
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
     *      Возвращает истину, когда текст ноды содержит тег с заданным именем.
     *      \param node - узел, с которого осуществляется поиск. 
     *      \param sname - имя тега для поиска 
     *      \param params - true если ищется обычный тег и false, если ищется тег секции 
     */
    boolean getNodeTags(Node node, String tagname, boolean params )
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
    /** 
     *      Вставляет новую строку в таблицу, заменяет теги на значения, удаляет тег секции из строки таблицы.
     *      Выполняет рекурсивный поиск узла, содержащего строку таблицы. У этого узла в OpenOffic'е есть
     *      специальное имя, которое распознается функцией. После того, как узел найден, строка строка дублируется, 
     *      а из текущей строки удаляются все теги секции, чтобы избежать мнократного размножения строк таблицы.
     *      \see searchTags()
     */
    void insertRowValues(Node node)
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
    
    /**
    *      Добавляет к тегу значение параметра \a tagName. После вызова этой функции тег не исчезает,
    *      и к нему можно добавить еще значения, которые добавятся к концу текста, содержащего тег.
    *      \param node -  узел к которому добавляется значение 
    *      \param sname - имя тега 
    */
    void insertTagsValues(Node node, String tagName){
        logger.debug("tag name = " + tagName + ", inserted");
        Node n = node; 
        n.setNodeValue(n.getNodeValue()+getValue(tagName));
    }
    
    /*
     * Удаляет все теги из документа, а также строки, в которых содержится тег секции
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
 /*
 *	Удаляет рекурсивно теги из \a node.
 *	\param node -  узел из которого нужно удалить теги 
 * 	\param section true, если надо удалить тег секции 
 */
    void clearTags(Node node, boolean section )
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
/*
 *	Рекурсивная функция. Удаляет строки, содержащие тег секции
*	\param node - узел из которого нужно удалить строки 
 */
    void clearRow(Node node)
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
/* Сохраняет шаблон в файл с заданным именем. Перед сохранением необходимо выполнить функцию cleanUpTags() чтобы удалить тэги из сохраняемого документа.
 * 
 */
    public boolean save( String fname ){
        if(docTpl!= null ){
            File template = new File(templateName);
            File out = new File(fname);
            try {
                XMLSerializer serializer = new XMLSerializer();
                OutputFormat of = new OutputFormat(docTpl);
                of.setEncoding("UTF-8");
                serializer.setOutputByteStream(new FileOutputStream("content.xml"));
                serializer.setOutputFormat(of);
                serializer.serialize(docTpl);
                File content = new File("content.xml");
                addFilesToExistingZip(template, out, new File[] {content} );
                content.delete();
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


