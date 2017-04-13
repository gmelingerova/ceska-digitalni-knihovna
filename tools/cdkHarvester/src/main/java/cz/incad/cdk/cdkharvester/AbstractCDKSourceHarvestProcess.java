package cz.incad.cdk.cdkharvester;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.ws.rs.core.MediaType;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.kramerius.Import;
import org.kramerius.replications.BasicAuthenticationClientFilter;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;

import cz.incad.cdk.cdkharvester.changeindex.AddField;
import cz.incad.cdk.cdkharvester.changeindex.ChangeField;
import cz.incad.cdk.cdkharvester.changeindex.PrivateConnectUtils;
import cz.incad.cdk.cdkharvester.changeindex.ResultsUtils;
import cz.incad.cdk.cdkharvester.foxmlprocess.ProcessFOXML;
import cz.incad.cdk.cdkharvester.iterator.CDKHarvestIteration;
import cz.incad.cdk.cdkharvester.iterator.CDKHarvestIterationException;
import cz.incad.cdk.cdkharvester.iterator.CDKHarvestIterationItem;
import cz.incad.cdk.cdkharvester.manageprocess.CheckLiveProcess;
import cz.incad.cdk.cdkharvester.timestamp.ProcessingTimestamps;
import cz.incad.kramerius.utils.IOUtils;
import cz.incad.kramerius.utils.conf.KConfiguration;
import cz.incad.kramerius.utils.pid.LexerException;
import cz.incad.kramerius.utils.pid.PIDParser;

public abstract class AbstractCDKSourceHarvestProcess implements CDKSourceHarvestProcess {

	public static final Logger LOGGER = Logger.getLogger(AbstractCDKSourceHarvestProcess.class.getName());

	public static String API_VERSION = "v4.6";
	public static int ROWS = 500;
	
	protected String harvestUrl;
	protected String k4Url;
	protected String sourceName;
	protected String collectionPid;
	protected String userName;
	protected String pswd;

	protected List<ProcessFOXML> processingChain = new ArrayList<ProcessFOXML>();

	protected Transformer transformer;

	protected CheckLiveProcess checkLiveProcesses;

	protected ProcessingTimestamps processingTimestamp;

	protected WebResource client(String url) {
		Client c = Client.create();
		c.setConnectTimeout(2000);
		c.setReadTimeout(60000);
		WebResource r = c.resource(url);
		r.addFilter(new BasicAuthenticationClientFilter(userName, pswd));
		return r;
	}

	public void postToIndex(String xmlcont) throws CDKReplicationException {
		String solrUrlString = getSolrUpdateEndpoint();
		Client c = Client.create();
		WebResource r = c.resource(solrUrlString);
		ClientResponse resp = r.accept(MediaType.TEXT_XML).type(MediaType.TEXT_XML)
				.entity(xmlcont, "text/xml; charset=UTF-8").post(ClientResponse.class);
		int status = resp.getStatus();
		if (status != 200) {
			String entity = resp.getEntity(String.class);
			throw new CDKReplicationException("couldn't index data because of " + entity);
		}
	}

	protected String getSolrUpdateEndpoint() {
		String solrUrlString = KConfiguration.getInstance().getConfiguration().getString("solrHost") + "/update";
		return solrUrlString;
	}

	protected String getSolrSelectEndpoint() {
		String solrUrlString = KConfiguration.getInstance().getConfiguration().getString("solrHost") + "/select";
		return solrUrlString;
	}

	public void replicate(String pid) throws CDKReplicationException {
		try {
			String url = k4Url + "/api/" + API_VERSION + "/cdk/" + pid + "/foxml?collection=" + collectionPid;
			LOGGER.log(Level.FINE, "get foxml from origin {0}...", url);
			PIDParser parser = new PIDParser(pid);
			if (!parser.isPagePid()) {
				if (!Utils.getSkipList().contains(pid)) {
					InputStream t = foxml(pid, url);
					ingest(t, pid);
					index(pid);
				} else {
					LOGGER.log(Level.INFO, "skipping pid {0} because of configuration", pid);
				}
			} else {
				LOGGER.info("page pid; github #16");
			}
		} catch (LexerException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
	}

	@Override
	public void addIntoChain(ProcessFOXML pxml) throws CDKReplicationException {
		this.processingChain.add(pxml);
	}

	@Override
	public void removeFromChain(ProcessFOXML pxml) throws CDKReplicationException {
		this.processingChain.remove(pxml);
	}

	@Override
	public List<ProcessFOXML> getChain() throws CDKReplicationException {
		return this.processingChain;
	}

	public org.json.JSONObject findDocFromCurrentIndex(String pid)
			throws UnsupportedEncodingException, URISyntaxException {
		org.json.JSONObject results = PrivateConnectUtils.findDoc(getSolrSelectEndpoint(), pid);
		return results;
	}

	protected void initVariables(String url, String name, String collectionPid, String userName, String pswd) {
		this.k4Url = url;
		this.sourceName = name;
		this.collectionPid = collectionPid;
		// setVirtualCollection();
		this.userName = userName;
		this.pswd = pswd;


	}

	protected void initImport() {
		Import.initialize(KConfiguration.getInstance().getProperty("ingest.user"),
				KConfiguration.getInstance().getProperty("ingest.password"));
	}

	protected void initTransformations()
			throws TransformerFactoryConfigurationError, TransformerConfigurationException {
		TransformerFactory tfactory = TransformerFactory.newInstance();
		InputStream stylesheet = this.getClass().getResourceAsStream("/cz/incad/cdk/cdkharvester/tr.xsl");
		StreamSource xslt = new StreamSource(stylesheet);
		this.setTransformer(tfactory.newTransformer(xslt));
	}

	public Transformer getTransformer() {
		return transformer;
	}

	public void setTransformer(Transformer transformer) {
		this.transformer = transformer;
	}

	public InputStream foxml(String pid, String url) {
		WebResource r = client(url);
		try {
			return r.accept(MediaType.APPLICATION_XML).get(InputStream.class);
		} catch (UniformInterfaceException ex2) {
			if (ex2.getResponse().getStatus() == 404) {
				LOGGER.log(Level.WARNING, "Call to {0} failed with message {1}. Skyping document.",
						new Object[] { url, ex2.getResponse().toString() });
				return null;
			} else {
				LOGGER.log(Level.WARNING, "Call to {0} failed. Retrying...", url);
				return r.accept(MediaType.APPLICATION_XML).get(InputStream.class);
			}
		} catch (Exception ex) {
			LOGGER.log(Level.WARNING, "Call to {0} failed. Retrying...", url);
			return r.accept(MediaType.APPLICATION_XML).get(InputStream.class);
		}
	}

	public void ingest(InputStream foxml, String pid) throws CDKReplicationException {
		try {
			if (foxml == null) {
				LOGGER.info("No inputstream for foxml");
				return;
			}

			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			IOUtils.copyStreams(foxml, bos);

			InputStream processingStream = new ByteArrayInputStream(bos.toByteArray());
			for (int i = 0, ll = this.processingChain.size(); i < ll; i++) {
				ProcessFOXML unit = processingChain.get(i);
				processingStream = new ByteArrayInputStream(unit.process(this.k4Url, pid, processingStream));
			}

			rawIngest(pid, processingStream);
		} catch (IOException e) {
			throw new CDKReplicationException(e);
		} catch (Exception e) {
			throw new CDKReplicationException(e);
		}

	}

	protected void rawIngest(String pid, InputStream processingStream) throws IOException {
		Import.ingest(processingStream, pid, null, null, false);
	}

	public void index(String pid) throws CDKReplicationException {
		try {
			if (pid.contains("@")) {
				LOGGER.info("Page pid; cannot index");
				return;
			}
			org.json.JSONObject results = findDocFromCurrentIndex(pid);
			if (ResultsUtils.docsExists(results)) {
				if (ResultsUtils.collectionExists(results)) {
					List<String> collections = ResultsUtils.disectCollections(results);
					if (!collections.contains(this.collectionPid)) {
						AddField addField = new AddField(pid, "collection", this.collectionPid);
						addField.addValueToArray(getSolrUpdateEndpoint());
					}
				} else {
					ChangeField chField = new ChangeField(pid, "collection", this.collectionPid);
					chField.changeField(getSolrUpdateEndpoint());
				}
			} else {

				try {
					String url = k4Url + "/api/" + API_VERSION + "/cdk/" + pid + "/solrxml";
					InputStream t = solrxml(url);

					StreamResult destStream = new StreamResult(new StringWriter());
					changeTranformationVariables();
					transformer.transform(new StreamSource(t), destStream);

					StringWriter sw = (StringWriter) destStream.getWriter();
					postToIndex(sw.toString());
				} catch (UniformInterfaceException e) {
					LOGGER.info("cannot index document");
				}
			}
		} catch (UnsupportedEncodingException e) {
			throw new CDKReplicationException(e);
		} catch (URISyntaxException e) {
			throw new CDKReplicationException(e);
		} catch (TransformerException e) {
			throw new CDKReplicationException(e);
		}
	}

	public InputStream solrxml(String url) {
		WebResource r = client(url);
		InputStream t = r.accept(MediaType.APPLICATION_XML).get(InputStream.class);
		return t;
	}

	public String getCollectionPid() {
		return collectionPid;
	}

	public void setCollectionPid(String collectionPid) {
		this.collectionPid = collectionPid;
	}

	protected void changeTranformationVariables() {
		transformer.setParameter("collectionPid", getCollectionPid());
		transformer.setParameter("solr_url", getSolrSelectEndpoint());
	}

	protected void commit() throws CDKReplicationException {
		String s = "<commit />";
		// logger.log(Level.FINE, "commit");
		postToIndex(s);

	}

	public static String reducePid(String pid) {
		// page pid
		if (pid.contains("/@")) {
			pid = pid.replace("/@", "@");
		}
		return pid;
	}

	/**
	 * Basic import process 
	 * @param sourcePid Source pid
	 * @param iterator Basic iterator
	 * @param timestaps Timestamps
	 * @throws CDKReplicationException
	 * @throws IOException
	 * @throws CDKHarvestIterationException
	 */
    protected void process(String sourcePid, CDKHarvestIteration iterator,@Nullable ProcessingTimestamps timestaps) throws CDKReplicationException, IOException, CDKHarvestIterationException {
		int processed = 0;
    	while(iterator.hasNext()) {
    		CDKHarvestIterationItem iter = iterator.next();
    		String pid = iter.getPid();
    		String timestamp = iter.getTimestamp();
    		if (timestamp != null) {
        		replicate(pid);
        		if (timestamp != null) {
            		timestaps.setTimestamp(sourcePid, timestaps.parse(timestamp));
        		}
                processed++;
    		} else {
        		replicate(pid);
                processed++;
    		}
            commit();
    	}
        commit();
        LOGGER.log(Level.INFO, "{0} processed", processed);
    }

}
