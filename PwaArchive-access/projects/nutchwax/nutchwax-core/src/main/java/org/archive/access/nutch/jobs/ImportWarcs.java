/*
 * $Id: ImportWarcs.java 1521 2018-03-00
 *
 * Copyright (C) 2003 Internet Archive.
 *
 * This file is part of the archive-access tools project
 * (http://sourceforge.net/projects/archive-access).
 *
 * The archive-access tools are free software; you can redistribute them and/or
 * modify them under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or any
 * later version.
 *
 * The archive-access tools are distributed in the hope that they will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License along with
 * the archive-access tools; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package org.archive.access.nutch.jobs;

import org.apache.commons.httpclient.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.*;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.ToolBase;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.crawl.MapWritable;
import org.apache.nutch.fetcher.Fetcher;
import org.apache.nutch.fetcher.FetcherOutput;
import org.apache.nutch.fetcher.FetcherOutputFormat;
import org.apache.nutch.global.Global;
import org.apache.nutch.metadata.Metadata;
import org.apache.nutch.metadata.Nutch;
import org.apache.nutch.net.URLFilters;
import org.apache.nutch.net.URLNormalizers;
import org.apache.nutch.parse.*;
import org.apache.nutch.scoring.ScoringFilterException;
import org.apache.nutch.scoring.ScoringFilters;
import org.apache.nutch.util.StringUtil;
import org.apache.nutch.util.mime.MimeType;
import org.apache.nutch.util.mime.MimeTypeException;
import org.apache.nutch.util.mime.MimeTypes;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.HtmlParser;
import org.apache.tika.parser.txt.CharsetDetector;
import org.apache.tika.parser.txt.CharsetMatch;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.Link;
import org.apache.tika.sax.LinkContentHandler;
import org.archive.access.nutch.Nutchwax;
import org.archive.access.nutch.NutchwaxConfiguration;
import org.archive.access.nutch.jobs.sql.SqlSearcher;
import org.archive.io.ArchiveRecordHeader;
import org.archive.io.warc.WARCRecord;
import org.archive.mapred.WARCMapRunner;
import org.archive.mapred.WARCRecordMapper;
import org.archive.mapred.WARCReporter;
import org.archive.util.Base32;
import org.archive.util.MimetypeUtils;
import org.archive.util.TextUtils;
import org.xml.sax.ContentHandler;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/*import org.apache.tika.metadata.Metadata;*/
//import org.apache.tika.mime.MediaType;
/*import org.apache.tika.mime.MimeTypes;*/
/*import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;*/


/**
 * Ingests WARCs writing WARC Record parse as Nutch FetcherOutputFormat.
 * FOF has five outputs:
 * <ul><li>crawl_fetch holds a fat CrawlDatum of all vitals including metadata.
 * Its written below by our {@link WaxFetcherOutputFormat} (innutch by
 * {@link FetcherOutputFormat}).  Here is an example CD: <pre>  Version: 4
 *  Status: 5 (fetch_success)
 *  Fetch time: Wed Mar 15 12:38:49 PST 2006
 *  Modified time: Wed Dec 31 16:00:00 PST 1969
 *  Retries since fetch: 0
 *  Retry interval: 0.0 days
 *  Score: 1.0
 *  Signature: null
 *  Metadata: collection:test arcname:IAH-20060315203614-00000-debord arcoffset:5127
 * </pre></li>
 * <li>crawl_parse has CrawlDatum of MD5s.  Used making CrawlDB.
 * Its obtained from above fat crawl_fetch CrawlDatum and written
 * out as part of the parse output done by {@link WaxParseOutputFormat}.
 * This latter class writes three files.  This crawl_parse and both
 * of the following parse_text and parse_data.</li>
 * <li>parse_text has text from parse.</li>
 * <li>parse_data has other metadata found by parse (Depends on
 * parser).  This is only input to linkdb.  The html parser
 * adds found out links here and content-type and discovered
 * encoding as well as advertised encoding, etc.</li>
 * <li>cdx has a summary line for every record processed.</li>
 * </ul>
 */
public class ImportWarcs extends ToolBase implements WARCRecordMapper
{
    public  final Log LOG = LogFactory.getLog(ImportWarcs.class);
    private final NumberFormat numberFormatter = NumberFormat.getInstance();

    private static final String WHITESPACE = "\\s+";

    public static final String ARCFILENAME_KEY = "arcname";
    public static final String ARCFILEOFFSET_KEY = "arcoffset";
    private static final String CONTENT_TYPE_KEY = "Content-Type";
    private static final String TEXT_TYPE = "text/";
    private static final String APPLICATION_TYPE = "application/";
    public static final String ARCCOLLECTION_KEY = "collection";
    public static final String WAX_SUFFIX = "wax.";
    public static final String WAX_COLLECTION_KEY = WAX_SUFFIX + ARCCOLLECTION_KEY;

    private static final String PDF_TYPE = "application/pdf";

    private boolean indexAll;
    private int contentLimit;
    private int pdfContentLimit;
    private MimeTypes mimeTypes;
    private String segmentName;
    private String collectionName;
    private int parseThreshold = -1;
    private boolean indexRedirects;
    private boolean sha1 = false;
    private boolean arcNameFromFirstRecord = true ;
    private String arcName;
    private String collectionType;
    private int timeoutIndexingDocument;


    /**
     * Usually the URL in first record looks like this:
     * filedesc://IAH-20060315203614-00000-debord.arc.  But in old
     * ARCs, it can look like this: filedesc://19961022/IA-000001.arc.
     */
    private static final Pattern FILEDESC_PATTERN =
            Pattern.compile("^(?:filedesc://)(?:[0-9]+\\/)?(.+)(?:\\.arc)$");

    private static final Pattern TAIL_PATTERN =
            Pattern.compile("(?:.*(?:/|\\\\))?(.+)(?:\\.arc|\\.arc\\.gz)$");

    /**
     * Buffer to reuse on each ARCRecord indexing.
     */
    private final byte[] buffer = new byte[1024 * 16];

    private final ByteArrayOutputStream contentBuffer =
            new ByteArrayOutputStream(1024 * 16);

    private URLNormalizers urlNormalizers;
    private URLFilters filters;

    private ParseUtil parseUtil;

    private static final Text CDXKEY = new Text("cdx");

    private TimeoutParsingThreadPool threadPool=new TimeoutParsingThreadPool(); // this is one pool of only one thread; it is not necessary to be static



    public ImportWarcs()
    {
        super();
    }

    public ImportWarcs(Configuration conf)
    {
        setConf(conf);
    }

    public void importWarcs(final Path arcUrlsDir, final Path segment,
                            final String collection)
            throws IOException
    {
        LOG.info("ImportWarcsSegment: " + segment + ", src: " + arcUrlsDir);
        System.out.println( "ImportWarcs segment: " + segment + ", src: " + arcUrlsDir );

        final JobConf job = new JobConf(getConf(), this.getClass());

        job.set(Nutch.SEGMENT_NAME_KEY, segment.getName());

        job.setInputPath(arcUrlsDir);

        job.setMapRunnerClass( WARCMapRunner.class ); // compatible with hadoop 0.14 TODO MC
        job.setMapperClass( this.getClass() );

        job.setInputFormat(TextInputFormat.class);

        job.setOutputPath(segment);
        job.setOutputFormat(WaxFetcherOutputFormat.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(FetcherOutput.class);

        // Pass the collection name out to the tasks IF non-null.
        if ((collection != null) && (collection.length() > 0))
        {
            job.set(ImportWarcs.WAX_SUFFIX + ImportWarcs.ARCCOLLECTION_KEY,
                    collection);
        }
        job.setJobName("import " + arcUrlsDir + " " + segment);

        JobClient.runJob(job);
        LOG.info("ImportWarcs: done");
    }

    public void configure(final JobConf job)
    {
        setConf(job);
        this.indexAll = job.getBoolean("wax.index.all", false);

        this.contentLimit = job.getInt("http.content.limit", 1024 * 100);
        final int pdfMultiplicand = job.getInt("wax.pdf.size.multiplicand", 10);
        this.pdfContentLimit = (this.contentLimit == -1) ? this.contentLimit
                : pdfMultiplicand * this.contentLimit;
        this.mimeTypes = MimeTypes.get(job.get("mime.types.file"));
        this.segmentName = job.get(Nutch.SEGMENT_NAME_KEY);

        // Get the rsync protocol handler into the mix.
        System.setProperty("java.protocol.handler.pkgs", "org.archive.net");

        // Format numbers output by parse rate logging.
        this.numberFormatter.setMaximumFractionDigits(2);
        this.numberFormatter.setMinimumFractionDigits(2);
        this.parseThreshold = job.getInt("wax.parse.rate.threshold", -1);

        this.indexRedirects = job.getBoolean("wax.index.redirects", false);

        this.sha1 = job.getBoolean("wax.digest.sha1", false);

        this.urlNormalizers = new URLNormalizers(job, URLNormalizers.SCOPE_FETCHER);
        this.filters = new URLFilters(job);

        this.parseUtil = new ParseUtil(job);

        this.collectionName = job.get(ImportWarcs.WAX_SUFFIX + ImportWarcs.ARCCOLLECTION_KEY);

        // Get ARCName by reading first record in ARC?  Otherwise, we parse
        // the name of the file we've been passed to find an ARC name.
        this.arcNameFromFirstRecord = job.getBoolean("wax.arcname.from.first.record", true);

        this.collectionType = job.get(Global.COLLECTION_TYPE);
        this.timeoutIndexingDocument = job.getInt(Global.TIMEOUT_INDEXING_DOCUMENT, -1);

        LOG.info("ImportWarcs collectionType: " + collectionType);
    }

    public Configuration getConf()
    {
        return this.conf;
    }

    public void setConf(Configuration c)
    {
        this.conf = c;
    }

    public void onWARCOpen()
    {
        // Nothing to do.
    }

    public void onWARCClose()
    {
        threadPool.closeAll(); // close the only thread created for this map
    }

    public void map(final WritableComparable key, final Writable value,
                    final OutputCollector output, final Reporter r)
            throws IOException
    {

        LOG.info( "MAP WARC" );
        // Assumption is that this map is being run by WARCMapRunner.
        // Otherwise, the below casts fail.
        String url = key.toString();

        WARCRecord rec = (WARCRecord)((ObjectWritable)value).get();
        WARCReporter reporter = (WARCReporter)r;

        // Its null first time map is called on an ARC.
        checkWArcName(rec);
        checkCollectionName();

        final ArchiveRecordHeader warcData = rec.getHeader();
        String oldUrl = url;

        try
        {
            url = urlNormalizers.normalize(url, URLNormalizers.SCOPE_FETCHER);
            url = filters.filter(url); // filter the url
        }
        catch (Exception e)
        {
            LOG.warn("Skipping record. Didn't pass normalization/filter " +
                    oldUrl + ": " + e.toString());

            return;
        }

        final long b = warcData.getContentBegin();
        final long l = warcData.getLength();
        final long recordLength = (l > b)? (l - b): l;

        // Look at WARCRecord meta data line mimetype. It can be empty.  If so,
        // two more chances at figuring it either by looking at HTTP headers or
        // by looking at first couple of bytes of the file.  See below.
        String warcRecordMimetype = warcData.getMimetype();
        String warcRecordType = (String) warcData.getHeaderValue("WARC-Type");
        if(warcRecordType == null || warcRecordMimetype == null) return;
        /*Check if WARC-TYPE=response and if this record is an http response*/
        if( ! "response".equals(warcRecordType.trim()) || !
                warcRecordMimetype.trim().startsWith("application/http")) {
            LOG.info("Skipping WARCTYPE: "+ warcData.getHeaderValue("WARC-Type") + " MimeType" + warcRecordMimetype );
            return;
        }
        LOG.info("WARC: http response record mimetype " + warcRecordMimetype);

        // TODO: Skip if unindexable type.
        int totalBytesRead = 0;
        // Read in first block. If mimetype still null, look for MAGIC.
        int len = rec.read(this.buffer, 0, this.buffer.length);
        this.contentBuffer.reset();

        // How much do we read total? If pdf, we will read more. If equal to -1,
        // read all.

        while ( len != -1 && totalBytesRead < this.contentLimit )
        {
            totalBytesRead += len;
            this.contentBuffer.write(this.buffer, 0, len);
            len = rec.read(this.buffer, 0, this.buffer.length);
            reporter.setStatusIfElapse("reading " + url);
        }
        final byte[] contentBytes = this.contentBuffer.toByteArray();

        //LOG.info("WARCContent: " + new String(contentBytes, "UTF-8"));

        ByteArrayInputStream contentArrayInputStream = new ByteArrayInputStream(contentBytes);
        String mimetype = null;

        String statusLinestr = HttpParser.readLine(contentArrayInputStream);
        /*LOG.info("WARCSTATUSSTR: "+ statusLinestr);*/
        StatusLine statusLine;
        int statusCode = -1;

        try{
            statusLine = new StatusLine(statusLinestr);
            statusCode= statusLine.getStatusCode();
        } catch (HttpException e ){
            LOG.error("HttpException parsing statusCode isIndex " , e);
            return;
        } catch (Exception e){
            LOG.error("Exception parsing statusCode isIndex " , e);
            return;
        }
        /*LOG.info("WARCSTATUSCode: "+ statusCode);*/
        Header[] headers = HttpParser.parseHeaders(contentArrayInputStream , "UTF-8");
        if (!isIndex(statusCode))
        {
            LOG.info("Not indexing this statusCode: ");
            throw new IOException("Invalid status code!");
            /*return;*/
        }
        final Metadata metaData = new Metadata(); /*Nutch Metadata*/
        org.apache.tika.metadata.Metadata tikaMetadata = new org.apache.tika.metadata.Metadata();
        boolean isChunked = false;
        String contentEncoding ="";
        /*boolean isGZiped = false;*/
        LOG.info( "WARC headers size = " + headers.length );
        for (int j = 0; j < headers.length; j++)
        {
            final Header header = headers[j];
            LOG.info( "header Name["+header.getName()+"] Value["+header.getValue()+"]" );
            tikaMetadata.add(header.getName(), header.getValue());
            if(header.getName().equals("Content-Type")){
                mimetype = header.getValue();
                metaData.set(header.getName(), header.getValue());
                metaData.set("content-type", mimetype);
                LOG.info("WARC mimetype: "+ mimetype);
            } else if (header.getName().equals("Transfer-Encoding")){
                if( ((String) header.getValue()).contains("chunked") ){
                    isChunked = true;
                    tikaMetadata.remove(header.getName()); /*removing from metada, anyway tika parser does not work with chunked http responses*/
                    LOG.info("WARC chunked record");
                    /*Dont save metadata since we are dealing with the chunked record*/
                }
            } else if (header.getName().equals("Content-Encoding")){
                contentEncoding = (String) header.getValue();
                metaData.set(header.getName(), header.getValue());
            }
        }
        LOG.info("Warc content mimetype: " + mimetype);
        if(skip(mimetype)){
            LOG.info("Null or invalid mimetype skipping...");
            return;
        }
        byte [] remainingBytes = null;
        if(isPDF(mimetype)){ /*PDFs have bigger readlimit in Bytes and we only know it is a pdf after parsing headers in WARC files*/
            LOG.info("PDF seeing if there are remaining Bytes");
            remainingBytes = readRemainingBytesPDF(totalBytesRead, rec, reporter,  len );
        }


        // This call to reporter setStatus pings the tasktracker telling it our
        // status and telling the task tracker we're still alive (so it doesn't
        // time us out).
        final String noSpacesMimetype =
                TextUtils.replaceAll(ImportWarcs.WHITESPACE,
                        ((mimetype == null || mimetype.length() <= 0)?
                                "TODO": mimetype),
                        "-");
        final String recordLengthAsStr = Long.toString(recordLength);

        reporter.setStatus(getStatus(url, oldUrl, recordLengthAsStr, noSpacesMimetype));

        /*TODO:: use byte array instead of inputstream, merge remaining Bytes with contentBytesNoHeaders*/
        byte[] contentBytesNoHeaders = null;

        if(isChunked){/*We need to do some parsing in the case of a Chunked http response*/
            LOG.info("Chunked response");
            contentBytesNoHeaders = getByteArrayFromInputStreamChunked(contentArrayInputStream, LOG);
            /*LOG.info("WARCINPUTSTREAM: " + inputStreamStringWithoutHtmlHeaders);*/
        }
        else{ /*Just send the bytes without the http headers to Apache Tika*/
            contentBytesNoHeaders = IOUtils.toByteArray(contentArrayInputStream);
        }
        int recordLengthNoHeaders = contentBytesNoHeaders.length;

        metaData.set("contentLength", "" +  recordLengthNoHeaders);

        LOG.info( "Content Length with headers: ["+recordLengthAsStr+"]" );
        LOG.info( "Content Length without headers: contentLength["+recordLengthNoHeaders+"]");

        if(recordLengthNoHeaders == 0){
            LOG.info("SKIPPING only headers RECORD, empty content length");
            return;
        }
        reporter.setStatusIfElapse("read headers on " + url);

        // Close the Record.  We're done with it.  Side-effect is calculation
        // of digest -- if we're digesting.
        rec.close();
        reporter.setStatusIfElapse("closed " + url);

        final CrawlDatum datum = new CrawlDatum();
        datum.setStatus(CrawlDatum.STATUS_FETCH_SUCCESS);



        //warcData.setDigest(digest); *Maybe not needed for WARCs*

        metaData.set(Nutch.SEGMENT_NAME_KEY, this.segmentName);

        // Score at this stage is 1.0f.
        metaData.set(Nutch.SCORE_KEY, Float.toString(datum.getScore()));

        //byte[] cleanedRecordContentBytes = inputStreamStringWithoutHtmlHeaders.getBytes();
        if(remainingBytes != null){
            /*If it is a pdf we need to append remainingBytes to the end of contentBytesNoHeaders*/
            byte[] destinationBytes = new byte[contentBytesNoHeaders.length + remainingBytes.length];
            System.arraycopy(contentBytesNoHeaders, 0, destinationBytes,0, contentBytesNoHeaders.length);
            System.arraycopy(remainingBytes, 0, destinationBytes, contentBytesNoHeaders.length, remainingBytes.length);
            contentBytesNoHeaders = destinationBytes;
        }
        // Calculate digest or use precalculated sha1.
        String digest = (this.sha1)? rec.getDigestStr():
                MD5Hash.digest(contentBytesNoHeaders).toString();
        metaData.set(Nutch.SIGNATURE_KEY, digest);

        // Set digest back into the warcData so available later when we write
        // CDX line.
        //final long startTime = System.currentTimeMillis();
		/*Content content = new Content(url, url, contentBytesNoHeaders, mimetype,
				metaData, getConf());*/

        String [] apacheExtractedContent = new String[2];
        Outlink[] outlinksTika = null;
        try{
            TikaConfig tikaConfig = TikaConfig.getDefaultConfig();
            apacheExtractedContent = parseUsingAutoDetect(contentBytesNoHeaders, tikaConfig,
                    tikaMetadata);
            LOG.info("Parsing Links");
            outlinksTika = parseLinks(contentBytesNoHeaders,  tikaConfig, tikaMetadata);
            LOG.info("End Parsing Links");

        } catch (IOException io){
            LOG.info("Error parsing tika: " + io);
            LOG.error("Error parsing tika: ", io);
            return; /*end map for this broken record*/
        } catch (Exception e){
            LOG.info("Error parsing tika: " + e);
            LOG.error("Error parsing tika: ", e);
            return;  /*end map for this broken record*/
        }

        //LOG.info("Nutch Content start print.... \n" + content.toString());
        //LOG.info("....................................\n");
        LOG.info("Apache Tika parsed text start print ....\n" + " " + apacheExtractedContent[0].split("\\s+"));
        //LOG.info("....................................\n");

        datum.setFetchTime(Nutchwax.getDate(getTs(warcData.getDate())));

        MapWritable mw = datum.getMetaData();

        if (mw == null)
        {
            mw = new MapWritable();
        }

        if (collectionType.equals(Global.COLLECTION_TYPE_MULTIPLE)) {
            mw.put(new Text(ImportWarcs.ARCCOLLECTION_KEY), new Text(SqlSearcher.getCollectionNameWithTimestamp(collectionName,getTs(warcData.getDate()))));
        }
        else {
            mw.put(new Text(ImportWarcs.ARCCOLLECTION_KEY), new Text(collectionName));
        }
        mw.put(new Text(ImportWarcs.ARCFILENAME_KEY), new Text(arcName));
        mw.put(new Text(ImportWarcs.ARCFILEOFFSET_KEY),
                new Text(Long.toString(warcData.getOffset())));
        datum.setMetaData(mw);

		/*TimeoutParsingThread tout=threadPool.getThread(Thread.currentThread().getId(),timeoutIndexingDocument);
		tout.setUrl(url);
		tout.setContent(content);
		tout.setParseUtil(parseUtil);
		tout.wakeupAndWait();

		ParseStatus parseStatus=tout.getParseStatus();
		Parse parse=tout.getParse();
		*/
        /*Todo: remove Nutch parsing replace with Tika for extracting the parseData*/
        //ParseData parseData = parse.getData();
        //LOG.info("Parse DATA \n" + parseData.toString());
        //LOG.info("Parse DATA - Title \n" + parseData.getTitle());
        //LOG.info("Parse DATA - MetaData \n" + parseData.getContentMeta().toString());
        //LOG.info("Parse DATA - Outlinks\n" + parseData.getOutlinks().toString());
        //LOG.info("Parse DATA - Parse Status\n" + parseData.getStatus().toString());


        ParseImpl tikaParseImpl = new ParseImpl(apacheExtractedContent[0], new ParseData(new ParseStatus(1),apacheExtractedContent[1],outlinksTika,metaData));
        /*using metadata from nutch parse, but the extracted text from Apache Tika*/

        reporter.setStatusIfElapse("parsed " + url);

        //LOG.info("Parsed URL: " + url);
        //LOG.info("Parsed Status: " + parseStatus.toString());
        /*LOG.info("Parsed text: "+ parse.getText());*/

		/*if (!parseStatus.isSuccess()) {
			final String status = formatToOneLine(parseStatus.toString());
			LOG.info("Error parsing: " + mimetype + " " + url + ": " + status);
			parse = null;
		}
		else {
			// Was it a slow parse?
			final double kbPerSecond = getParseRate(startTime,
					(contentBytes != null) ? contentBytes.length : 0);

			if (LOG.isDebugEnabled())
			{
				LOG.debug(getParseRateLogMessage(url,
						noSpacesMimetype, kbPerSecond));
			}
			else if (kbPerSecond < this.parseThreshold)
			{
				LOG.warn(getParseRateLogMessage(url, noSpacesMimetype,
						kbPerSecond));
			}
		}
		*/
        Writable v = new FetcherOutput(datum, null, tikaParseImpl);
        if (collectionType.equals(Global.COLLECTION_TYPE_MULTIPLE)) {
            LOG.info("multiple: "+SqlSearcher.getCollectionNameWithTimestamp(this.collectionName,getTs(warcData.getDate()))+" "+url);
            output.collect(Nutchwax.generateWaxKey(url,SqlSearcher.getCollectionNameWithTimestamp(this.collectionName,getTs(warcData.getDate()))), v);
        }
        else {
            output.collect(Nutchwax.generateWaxKey(url, this.collectionName), v);
        }
    }

    private byte[] readRemainingBytesPDF(int totalBytesRead, WARCRecord rec, WARCReporter reporter, int len ) throws IOException {
        // Read in first block. If mimetype still null, look for MAGIC.
        LOG.info("attempting to read more bytes from PDF");
        final ByteArrayOutputStream pdfcontentBuffer =new ByteArrayOutputStream(1024 * 16);
        while ( len != -1 && totalBytesRead < this.pdfContentLimit )
        {
            /*Reading more for PDF*/
            LOG.info("extra reading for PDF file");
            totalBytesRead += len;
            pdfcontentBuffer.write(this.buffer, 0, len);
            len = rec.read(this.buffer, 0, this.buffer.length);
            reporter.setStatusIfElapse("reading ");
        }
        return pdfcontentBuffer.toByteArray();
    }

    public void setCollectionName(String collectionName)
    {
        this.collectionName = collectionName;
        checkCollectionName();
    }

    public String getArcName()
    {
        return this.arcName;
    }

    public String getTs(String dateWarc){
        /*dateWarc in Format 2018-04-03T12:53:43Z */
        String year = "";
        String month = "";
        String day = "";
        String hour = "";
        String minute = "";
        String second = "";

        try{
            SimpleDateFormat thedate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", new Locale("pt", "PT"));
            Calendar mydate = thedate.getCalendar();

            year +=  mydate.get(Calendar.YEAR);
            int monthInt = mydate.get(Calendar.MONTH) + 1;
            int dayInt = mydate.get(Calendar.DAY_OF_MONTH);
            int hourInt = mydate.get(Calendar.HOUR_OF_DAY);
            int minuteInt = mydate.get(Calendar.MINUTE);
            int secondInt = mydate.get(Calendar.SECOND);
            month = monthInt < 10 ? "0" + monthInt : ""+monthInt;
            day = dayInt < 10 ? "0" + dayInt : ""+dayInt;
            hour = hourInt < 10 ? "0" + hourInt : ""+hourInt;
            minute = minuteInt < 10 ? "0" + minuteInt : ""+minuteInt;
            second = secondInt < 10 ? "0" + secondInt : ""+secondInt;

            LOG.info("WARC getTs: " + year + month + day + hour + minute + second );
        } catch (Exception e ){
            LOG.info("WARC getTS: error parsing date");
            return null;
        }
        return year + month + day + hour + minute + second;
    }

    public void checkWArcName(WARCRecord rec)
    {
        this.arcName= (String) rec.getHeader().getHeaderValue("reader-identifier"); /*url with path to arc*/
        this.arcName = arcName.substring(arcName.lastIndexOf('/')+1, arcName.length()); /*filename*/
        this.arcName = arcName.replace(".warc.gz", "");   /*filename without extension*/
        LOG.info("WARCNAME: " + this.arcName);
    }

    protected boolean checkCollectionName()
    {
        if ((this.collectionName != null) && this.collectionName.length() > 0)
        {
            return true;
        }

        throw new NullPointerException("Collection name can't be empty");
    }

    /**
     * @param bytes Array of bytes to examine for an EOL.
     * @return Count of end-of-line characters or zero if none.
     */
    private int getEolCharsCount(byte [] bytes) {
        int count = 0;
        if (bytes != null && bytes.length >=1 &&
                bytes[bytes.length - 1] == '\n') {
            count++;
            if (bytes.length >=2 && bytes[bytes.length -2] == '\r') {
                count++;
            }
        }
        return count;
    }


    /**
     * @param rec ARC Record to test.
     * @return True if we are to index this record.
     */
    protected boolean isIndex( int statusCode)
    {
        return ((statusCode >= 200) && (statusCode < 300))
                || (this.indexRedirects && ((statusCode >= 300) &&
                (statusCode < 400)));
    }

    private byte[] getByteArrayFromInputStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[inputStream.available()];
        int read = 0;
        while ((read = inputStream.read(buffer, 0, buffer.length)) != -1) {
            baos.write(buffer, 0, read);
        }
        baos.flush();
        return  baos.toByteArray();
    }


    private static String getStringFromInputStream(InputStream is) {

        BufferedReader br = null;
        StringBuilder sb = new StringBuilder();

        String line;
        try {

            br = new BufferedReader(new InputStreamReader(is));
            while ((line = br.readLine()) != null) {
                sb.append(line + "\n");
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return sb.toString();
    }

    private static byte[] getByteArrayFromInputStreamChunked(InputStream is, Log LOG) {
        ChunkedInputStream cis = null;
        int i = 0;
        int currentChar;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        byte[] unchunkedData = null;

        try {
            cis = new ChunkedInputStream(is);

            // read till the end of the stream
            while((currentChar = cis.read(buffer))!= -1) {
                bos.write(buffer, 0, currentChar);
            }
            unchunkedData = bos.toByteArray();
            bos.close();
        } catch(IOException e) {
            // if any I/O error occurs
            e.printStackTrace();
        } finally {
            // releases any system resources associated with the stream
            if(is!=null){
                try{
                    is.close();
                } catch (IOException e){
                    LOG.error("ERROR closing InputStream chunked record" , e);
                }
            }
            if(cis!=null){
                try{
                    cis.close();
                } catch (IOException e){
                    LOG.error("ERROR closing ChunkedInputStream chunked record", e);
                }
            }
        }
        return unchunkedData;
    }


    private static String getStringFromInputStreamChunked(InputStream is, Log LOG) {
        ChunkedInputStream cis = null;
        int i = 0;
        int currentChar;
        String result ="";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        byte[] unchunkedData = null;

        try {
            cis = new ChunkedInputStream(is);

            // read till the end of the stream
            while((currentChar = cis.read(buffer))!= -1) {
                bos.write(buffer, 0, currentChar);
            }
            unchunkedData = bos.toByteArray();
            bos.close();
            result = new String(unchunkedData, "UTF-8");
        } catch(IOException e) {
            // if any I/O error occurs
            e.printStackTrace();
        } finally {
            // releases any system resources associated with the stream
            if(is!=null){
                try{
                    is.close();
                } catch (IOException e){
                    LOG.error("ERROR closing InputStream chunked record" , e);
                }
            }
            if(cis!=null){
                try{
                    cis.close();
                } catch (IOException e){
                    LOG.error("ERROR closing ChunkedInputStream chunked record", e);
                }
            }
        }
        LOG.info("WARC Chunked Result: " + result);
        return result;
    }
    private boolean isPDF(String mimetype){
        return (PDF_TYPE.equals(mimetype));
    }

    /*This function returns a string array where the first position contains the parsed text and the second contains the title of the document*/
    public static String[] parseUsingAutoDetect(byte[] content, TikaConfig tikaConfig,
                                                org.apache.tika.metadata.Metadata metadata) throws Exception {
        AutoDetectParser parser = new AutoDetectParser(tikaConfig);
        ContentHandler handler = new BodyContentHandler(-1); /*No maximum read limit of characters default is 10 000*/
        TikaInputStream stream = TikaInputStream.get(content, metadata);
        parser.parse(stream, handler, metadata, new ParseContext());
        String title = metadata.get("title");
        String [] result = new String[2];
        if(title != null){ /*For historical reasons adding title to the text extracted*/
            result[0] = title + " ";
        }
        result[0] +=handler.toString();
        result[1] = title;
        return result;
    }

    /*Use apache tika to extract outlinks and convert them to Nutch Outlink format*/
    public  Outlink[] parseLinks(byte[] content, TikaConfig tikaConfig,
                                 org.apache.tika.metadata.Metadata metadata) throws Exception {
        List<Outlink> outlinksList = new ArrayList<Outlink>();
        HtmlParser parser = new HtmlParser();
        LinkContentHandler handler = new LinkContentHandler();
        TikaInputStream stream = TikaInputStream.get(content, metadata);
        parser.parse(stream, handler, metadata, new ParseContext());
        List<Link> links = handler.getLinks();
        Iterator<Link> iter = links.iterator();
        while(iter.hasNext()) {
            Link link = iter.next();
            Outlink outlink = null;
            try{
                outlink = new Outlink(link.getUri(),link.getText() , conf);
                outlinksList.add(outlink );
                //LOG.info("OUTLINK_URI" +link.getUri());
                //LOG.info("OUTLINK_TEXT:" + link.getText());
            } catch(MalformedURLException e){ /*Nutch interface only accepts valid uris*/
                continue;
            }
        }
        /*convert list to array and return it*/
        return outlinksList.toArray(new Outlink[outlinksList.size()]);
    }


    private static String getStringFromInputStreamChunkedBefore(InputStream is, Log LOG) {
        /*Ignore first and last lines (number of bytes to read and 0)*/
        BufferedReader br = null;
        StringBuilder sb = new StringBuilder();

        try {

            br = new BufferedReader(new InputStreamReader(is));
            String bytesToReadString = br.readLine();
            int bytesToRead = Integer.parseInt(bytesToReadString, 16);
            if(bytesToRead < 1) return null;
            char [] bytesCbuf = new char[bytesToRead];
            String bytesCbufString ="";
            while (bytesToRead > 0) {
                br.read(bytesCbuf, 0, bytesToRead);
                bytesCbufString = String.valueOf(bytesCbuf);
                LOG.info("\nWARC Chunk Bytes to Read: " + bytesToRead+"\n");
                LOG.info("\nWARC Chunk: " + bytesCbufString+"\n");
                sb.append(bytesCbufString);
                /*Read next line if equals to 0 no more bytes to Read*/
                bytesToReadString = br.readLine(); /*there is always a \n left to read*/
                if (bytesToReadString.equals("")) {
                    bytesToReadString = br.readLine();
                }
                bytesToRead = Integer.parseInt(bytesToReadString, 16);
                bytesCbuf = new char[bytesToRead];
                bytesCbufString ="";
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return sb.toString();
    }

    protected Charset detectCharsetImpl(byte[] buffer) throws Exception
    {
        CharsetDetector detector = new CharsetDetector();
        detector.setText(buffer);
        CharsetMatch match = detector.detect();

        if(match != null && match.getConfidence() > 35)
        {
            try
            {
                return Charset.forName(match.getName());
            }
            catch(UnsupportedCharsetException e)
            {
                this.LOG.info("Charset detected as " + match.getName() + " but the JVM does not support this, detection skipped");
            }
        }
        return null;
    }

    protected String getStatus(final String url, String oldUrl,
                               final String recordLengthAsStr, final String noSpacesMimetype)
    {
        // If oldUrl is same as url, don't log.  Otherwise, log original so we
        // can keep url originally imported.
        if (oldUrl.equals(url))
        {
            oldUrl = "-";
        }

        StringBuilder sb = new StringBuilder(128);
        sb.append("adding ");
        sb.append(url);
        sb.append(" ");
        sb.append(oldUrl);
        sb.append(" ");
        sb.append(recordLengthAsStr);
        sb.append(" ");
        sb.append(noSpacesMimetype);

        return sb.toString();
    }

    protected String formatToOneLine(final String s)
    {
        final StringBuffer sb = new StringBuffer(s.length());

        for (final StringTokenizer st = new StringTokenizer(s, "\t\n\r");
             st.hasMoreTokens(); sb.append(st.nextToken()))
        {
            ;
        }

        return sb.toString();
    }


    protected String getParseRateLogMessage(final String url,
                                            final String mimetype, final double kbPerSecond)
    {
        return url + " " + mimetype + " parse KB/Sec "
                + this.numberFormatter.format(kbPerSecond);
    }

    protected double getParseRate(final long startTime, final long len)
    {
        // Get indexing rate:
        long elapsedTime = System.currentTimeMillis() - startTime;
        elapsedTime = (elapsedTime == 0) ? 1 : elapsedTime;

        return (len != 0) ? ((double) len / 1024)
                / ((double) elapsedTime / 1000) : 0;
    }

    protected boolean skip(final String mimetype)
    {
        boolean decision = false;

        /*We Are only indexing text* and application* mimetypes  */
        /*We are also excluding CSS, javascript and XML */

        if ((mimetype == null)
                || (!mimetype.startsWith(ImportWarcs.TEXT_TYPE) && !mimetype.startsWith(ImportWarcs.APPLICATION_TYPE))
                || (mimetype.startsWith(ImportWarcs.TEXT_TYPE) && mimetype.toLowerCase().contains("css"))
                || (mimetype.toLowerCase().contains("xml"))
                || (mimetype.toLowerCase().contains("javascript")))
        {
            // Skip any but basic types.
            decision = true;
        }
        return decision;
    }

    protected String getMimetype(final String mimetype, final MimeTypes mts,
                                 final String url)
    {
        if (mimetype != null && mimetype.length() > 0)
        {
            return checkMimetype(mimetype.toLowerCase());
        }

        if (mts != null && url != null)
        {
            final MimeType mt = mts.getMimeType(url);

            if (mt != null)
            {
                return checkMimetype(mt.getName().toLowerCase());
            }
        }

        return null;
    }

    protected static String checkMimetype(String mimetype)
    {
        MimeType aux = null;
        if ((mimetype == null) || (mimetype.length() <= 0) ||
                mimetype.startsWith(MimetypeUtils.NO_TYPE_MIMETYPE))
        {
            return null;
        }

        // Test the mimetype makes sense. If not, clear it.
        try{
            aux = new MimeType(mimetype);
        } catch ( final MimeTypeException e ) {
            mimetype = null;
        }

        return mimetype;
    }

    private static String tryUnzip(byte[] zipped) {
        try {
            ByteArrayInputStream is = new ByteArrayInputStream(zipped);
            ChunkedInputStream cs = new ChunkedInputStream(is);
            GZIPInputStream zs = new GZIPInputStream(cs);
            BufferedReader r = new BufferedReader(new InputStreamReader(zs));
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                result.append(line).append("\n");
            }
            return result.toString();
        }
        catch (IOException e) {
            if (!"Not in GZIP format".equals(e.getMessage())) {
                e.printStackTrace();
            }
            return null;
        }
    }

    /**
     * Override of nutch FetcherOutputFormat so I can substitute my own
     * ParseOutputFormat, {@link WaxParseOutputFormat}.  While I'm here,
     * removed content references.  NutchWAX doesn't save content.
     * @author stack
     */
    public static class WaxFetcherOutputFormat extends FetcherOutputFormat
    {
        public RecordWriter getRecordWriter(final FileSystem fs,
                                            final JobConf job, final String name, Progressable progress)
                throws IOException
        {
            Path f = new Path(job.getOutputPath(), CrawlDatum.FETCH_DIR_NAME);
            final Path fetch = new Path(f, name);
            final MapFile.Writer fetchOut = new MapFile.Writer(job, fs,
                    fetch.toString(), Text.class, CrawlDatum.class);

            // Write a cdx file.  Write w/o compression.
            Path cdx = new Path(new Path(job.getOutputPath(), "cdx"), name);
            final SequenceFile.Writer cdxOut = SequenceFile.createWriter(fs,
                    job, cdx, Text.class, Text.class,
                    SequenceFile.CompressionType.NONE);

            return new RecordWriter()
            {
                private RecordWriter parseOut;

                // Initialization
                {
                    if (Fetcher.isParsing(job))
                    {
                        // Here is nutchwax change, using WaxParseOutput
                        // instead of ParseOutputFormat.
                        this.parseOut = new WaxParseOutputFormat().
                                getRecordWriter(fs, job, name, null);
                    }
                }

                public void write(WritableComparable key, Writable value)
                        throws IOException
                {
                    FetcherOutput fo = (FetcherOutput)value;
                    MapWritable mw = fo.getCrawlDatum().getMetaData();
                    Text cdxLine = (Text)mw.get(ImportWarcs.CDXKEY);

                    if (cdxLine != null)
                    {
                        cdxOut.append(key, cdxLine);
                    }

                    mw.remove(ImportWarcs.CDXKEY);
                    fetchOut.append(key, fo.getCrawlDatum());

                    if (fo.getParse() != null)
                    {
                        parseOut.write(key, fo.getParse());
                    }
                }

                public void close(Reporter reporter) throws IOException
                {
                    fetchOut.close();
                    cdxOut.close();

                    if (parseOut != null)
                    {
                        parseOut.close(reporter);
                    }
                }
            };
        }
    }

    /**
     * Copy so I can add collection prefix to produced signature and link
     * CrawlDatums.
     * @author stack
     */
    public static class WaxParseOutputFormat extends ParseOutputFormat
    {
        public final Log LOG = LogFactory.getLog(WaxParseOutputFormat.class);

        private URLNormalizers urlNormalizers;
        private URLFilters filters;
        private ScoringFilters scfilters;

        public RecordWriter getRecordWriter(FileSystem fs, JobConf job,
                                            String name, Progressable progress)
                throws IOException
        {
            // Extract collection prefix from key to use later when adding
            // signature and link crawldatums.

            this.urlNormalizers =
                    new URLNormalizers(job, URLNormalizers.SCOPE_OUTLINK);
            this.filters = new URLFilters(job);
            this.scfilters = new ScoringFilters(job);

            final float interval =
                    job.getFloat("db.default.fetch.interval", 30f);
            final boolean ignoreExternalLinks =
                    job.getBoolean("db.ignore.external.links", false);
            final boolean sha1 = job.getBoolean("wax.digest.sha1", false);

            Path text = new Path(new Path(job.getOutputPath(),
                    ParseText.DIR_NAME), name);
            Path data = new Path(new Path(job.getOutputPath(),
                    ParseData.DIR_NAME), name);
            Path crawl = new Path(new Path(job.getOutputPath(),
                    CrawlDatum.PARSE_DIR_NAME), name);

            final MapFile.Writer textOut = new MapFile.Writer(job, fs,
                    text.toString(), Text.class, ParseText.class,
                    CompressionType.RECORD);

            final MapFile.Writer dataOut = new MapFile.Writer(job, fs,
                    data.toString(), Text.class, ParseData.class);

            final SequenceFile.Writer crawlOut = SequenceFile.createWriter(fs,
                    job, crawl, Text.class, CrawlDatum.class);

            return new RecordWriter()
            {
                public void write(WritableComparable key, Writable value)
                        throws IOException
                {
                    // Test that I can parse the key before I do anything
                    // else. If not, write nothing for this record.
                    String collection = null;
                    String fromUrl = null;
                    String fromHost = null;
                    String toHost = null;

                    try
                    {
                        collection = Nutchwax.getCollectionFromWaxKey(key);
                        fromUrl = Nutchwax.getUrlFromWaxKey(key);
                    }
                    catch (IOException ioe)
                    {
                        LOG.warn("Skipping record. Can't parse " + key, ioe);

                        return;
                    }

                    if (fromUrl == null || collection == null)
                    {
                        LOG.warn("Skipping record. Null from or collection " +
                                key);

                        return;
                    }

                    Parse parse = (Parse)value;

                    textOut.append(key, new ParseText(parse.getText()));
                    ParseData parseData = parse.getData();


                    // recover the signature prepared by Fetcher or ParseSegment
                    String sig = parseData.getContentMeta().get(
                            Nutch.SIGNATURE_KEY);

                    if (sig != null)
                    {
                        byte[] signature = (sha1)?
                                Base32.decode(sig): StringUtil.fromHexString(sig);

                        if (signature != null)
                        {
                            // append a CrawlDatum with a signature
                            CrawlDatum d = new CrawlDatum(
                                    CrawlDatum.STATUS_SIGNATURE, 0.0f);
                            d.setSignature(signature);
                            crawlOut.append(key, d);
                        }
                    }

                    // collect outlinks for subsequent db update
                    Outlink[] links = parseData.getOutlinks();
                    if (ignoreExternalLinks)
                    {
                        try
                        {
                            fromHost = new URL(fromUrl).getHost().toLowerCase();
                        }
                        catch (MalformedURLException e)
                        {
                            fromHost = null;
                        }
                    }
                    else
                    {
                        fromHost = null;
                    }

                    String[] toUrls = new String[links.length];
                    int validCount = 0;

                    for (int i = 0; i < links.length; i++)
                    {
                        String toUrl = links[i].getToUrl();

                        try
                        {
                            toUrl = urlNormalizers.normalize(toUrl,URLNormalizers.SCOPE_OUTLINK);
                            toUrl = filters.filter(toUrl); // filter the url
                            if (toUrl==null) {
                                LOG.warn("Skipping url (target) because is null."); // TODO MC remove
                            }
                        }
                        catch (Exception e)
                        {
                            toUrl = null;
                        }

                        // ignore links to self (or anchors within the page)
                        if (fromUrl.equals(toUrl))
                        {
                            toUrl = null;
                        }

                        if (toUrl != null)
                        {
                            validCount++;
                        }

                        toUrls[i] = toUrl;
                    }

                    CrawlDatum adjust = null;

                    // compute score contributions and adjustment to the
                    // original score
                    for (int i = 0; i < toUrls.length; i++)
                    {
                        if (toUrls[i] == null)
                        {
                            continue;
                        }

                        if (ignoreExternalLinks)
                        {
                            try
                            {
                                toHost = new URL(toUrls[i]).getHost().
                                        toLowerCase();
                            }
                            catch (MalformedURLException e)
                            {
                                toHost = null;
                            }

                            if (toHost == null || ! toHost.equals(fromHost))
                            {
                                // external links
                                continue; // skip it
                            }
                        }

                        CrawlDatum target = new CrawlDatum(
                                CrawlDatum.STATUS_LINKED, interval);
                        Text fromURLUTF8 = new Text(fromUrl);
                        Text targetUrl = new Text(toUrls[i]);
                        adjust = null;

                        try
                        {
                            // Scoring now expects first two arguments to be
                            // URLs (More reason to do our own scoring).
                            // St.Ack
                            adjust = scfilters.distributeScoreToOutlink(
                                    fromURLUTF8, targetUrl, parseData,
                                    target, null, links.length, validCount);
                        }
                        catch (ScoringFilterException e)
                        {
                            if (LOG.isWarnEnabled())
                            {
                                LOG.warn("Cannot distribute score from " + key
                                        + " to " + target + " - skipped ("
                                        + e.getMessage());
                            }

                            continue;
                        }

                        Text targetKey =
                                Nutchwax.generateWaxKey(targetUrl, collection);
                        crawlOut.append(targetKey, target);
                        if (adjust != null)
                        {
                            crawlOut.append(key, adjust);
                        }
                    }

                    dataOut.append(key, parseData);
                }

                public void close(Reporter reporter) throws IOException
                {
                    textOut.close();
                    dataOut.close();
                    crawlOut.close();
                }
            };
        }
    }

    public void close()
    {
        // Nothing to close.
    }

    public static void doImportUsage(final String message,
                                     final int exitCode)
    {
        if (message != null && message.length() > 0)
        {
            System.out.println(message);
        }

        System.out.println("Usage: hadoop jar nutchwax.jar import <input>" +
                " <output> <collection>");
        System.out.println("Arguments:");
        System.out.println(" input       Directory of files" +
                " listing ARC URLs to import");
        System.out.println(" output      Directory to import to. Inport is " +
                "written to a subdir named");
        System.out.println("             for current date plus collection " +
                "under '<output>/segments/'");
        System.out.println(" collection  Collection name. Added to" +
                " each resource.");
        System.exit(exitCode);
    }

    public static void main(String[] args) throws Exception
    {
        int res = new ImportWarcs().
                doMain(NutchwaxConfiguration.getConfiguration(), args);

        System.exit(res);
    }

    public int run(final String[] args) throws Exception
    {
        if (args.length != 3)
        {
            doImportUsage("ERROR: Wrong number of arguments passed.", 2);
        }

        // Assume list of ARC urls is first arg and output dir the second.
        try
        {
            importWarcs(new Path(args[0]), new Path(args[1]), args[2]);
            return 0;
        }
        catch(Exception e)
        {
            LOG.fatal("ImportWARCsFAILURE: " + StringUtils.stringifyException(e));

            return -1;
        }
    }
}
