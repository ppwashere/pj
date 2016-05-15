package com.qwefgh90.io.handyfinder.springweb.service;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.InvalidParameterException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.activity.InvalidActivityException;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.flexible.core.QueryNodeException;
import org.apache.lucene.queryparser.flexible.standard.StandardQueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.search.highlight.TextFragment;
import org.apache.lucene.search.highlight.TokenSources;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.poi.util.LongField;

import com.qwefgh90.io.handyfinder.springweb.model.Directory;
import com.qwefgh90.io.handyfinder.springweb.websocket.InteractionInvoker;
import com.qwefgh90.io.handyfinder.springweb.websocket.ProgressCommand;
import com.qwefgh90.io.jsearch.JSearch;

/**
 * document indexing, search class based on Lucene
 * 
 * @author choechangwon
 * @since 16/05/13
 *
 */
public class LuceneHandler implements Cloneable, AutoCloseable {

	private static Log log = LogFactory.getLog(LuceneHandler.class);

	// writer
	private Path indexWriterPath;
	private org.apache.lucene.store.Directory dir;
	private Analyzer analyzer;
	private IndexWriterConfig iwc;
	private IndexWriter writer;

	// reader / searcher
	private DirectoryReader indexReader;
	private IndexSearcher searcher;
	private StandardQueryParser parser;

	// state
	private enum INDEX_WRITE_STATE {
		START, TERMINATE
	}

	private INDEX_WRITE_STATE writeState;
	private int currentProgress = 0;
	private int totalProcess = 0;
	private InteractionInvoker invokerForCommand;

	private void updateHandlerState(INDEX_WRITE_STATE state) {
		this.writeState = state;
		if (state == INDEX_WRITE_STATE.TERMINATE) {
			currentProgress = 0;
			totalProcess = 0;
		}
	}
	/**
	 * current indexing state
	 * @return
	 */
	public INDEX_WRITE_STATE getWriteState() { return writeState; }

	private static ConcurrentHashMap<String, LuceneHandler> map = new ConcurrentHashMap<>();


	/**
	 * static factory method
	 * 
	 * @param indexWriterPath
	 *            : path where index stored
	 * @return object identified by path
	 */
	public static LuceneHandler getInstance(Path indexWriterPath, InteractionInvoker invoker) {
		if (Files.isDirectory(indexWriterPath.getParent()) && Files.isWritable(indexWriterPath.getParent())) {
			String pathString = indexWriterPath.toAbsolutePath().toString();
			if (!map.containsKey(pathString)) {
				LuceneHandler newInstance = new LuceneHandler();
				newInstance.writerInit(indexWriterPath);
				newInstance.invokerForCommand = invoker;
				map.put(pathString, newInstance);
			}
			return map.get(pathString);
		}
		throw new InvalidParameterException("invalid path for index writer. \n check directory and write permission.");
	}

	private LuceneHandler() {
	}

	/**
	 * object initialization identified by path
	 * 
	 * @param path
	 */
	private void writerInit(Path path) {
		try {
			indexWriterPath = path;
			dir = FSDirectory.open(path);
			analyzer = new StandardAnalyzer();
			iwc = new IndexWriterConfig(analyzer);
			writer = new IndexWriter(dir, iwc);
			if (writer.numDocs() == 0)
				writer.addDocument(new Document());
			writer.commit();
			@SuppressWarnings("unused")
			int count = writer.numDocs();
			indexReader = DirectoryReader.open(dir); // commit() is important
			// for real-time search
			searcher = new IndexSearcher(indexReader);
			parser = new StandardQueryParser();
			parser.setAllowLeadingWildcard(true);
		} catch (IOException e) {
			throw new RuntimeException("lucene IndexWriter initialization is failed");
		}
	}

	/**
	 * handyfinder object indexing API
	 * 
	 * @param list
	 * @throws IOException
	 * @throws IndexException 
	 */
	public void indexDirectories(List<Directory> list) throws IOException, IndexException {
		if(INDEX_WRITE_STATE.START == writeState)
			throw new IndexException("already indexing");
		checkIndexWriter();
		
		updateHandlerState(INDEX_WRITE_STATE.START);
		totalProcess = sizeOfindexDirectories(list);
		for (Directory dir : list) {
			Path tmp = Paths.get(dir.getPathString());
			if (dir.isRecusively())
				indexDirectory(tmp, true);
			else
				indexDirectory(tmp, false);
		}
		updateHandlerState(INDEX_WRITE_STATE.TERMINATE);
	}

	/**
	 * single directory indexing API
	 * 
	 * @param path
	 * @param recursively
	 * @throws IOException
	 */
	public void indexDirectory(Path path, boolean recursively) throws IOException {
		checkIndexWriter();
		
		if (Files.isDirectory(path)) {
			Path rootDirectory = path;
			if (recursively) {
				Files.walkFileTree(rootDirectory, new SimpleFileVisitor<Path>() {
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						if (attrs.isRegularFile()) {
							index(file);
							currentProgress++;
							invokerForCommand.updateProgress(currentProgress, file, totalProcess);
						}
						return FileVisitResult.CONTINUE;
					}
				});
			} else {
				Files.walkFileTree(rootDirectory, EnumSet.noneOf(FileVisitOption.class), 1,
						new SimpleFileVisitor<Path>() {
							public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
								if (attrs.isRegularFile()) {
									index(file);
									currentProgress++;
									invokerForCommand.updateProgress(currentProgress, file,
											totalProcess);
								}
								return FileVisitResult.CONTINUE;
							}
						});
			}
		}
	}

	/**
	 * single file indexing API commit() call at end
	 * 
	 * @param path
	 * @throws IOException
	 * @throws ParseException
	 */
	public void index(Path path) throws IOException {
		Document doc = new Document();

		FieldType type = new FieldType();
		type.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
		type.setStored(true);
		type.setStoreTermVectors(true);
		type.setStoreTermVectorOffsets(true);

		String contents = JSearch.extractContentsFromFile(path.toFile());

		StringField pathStringField = new StringField("pathString", path.toAbsolutePath().toString(), Store.YES);
		Field contentsField = new Field("contents", contents, type);

		
		doc.add(pathStringField);
		doc.add(contentsField);
		writer.updateDocument(new Term("pathString", path.toAbsolutePath().toString()), doc);
		log.info("indexing complete : " + path);
		writer.commit(); // commit() is important for real-time search
	}
	/**
	 * search full string which contains space charactor. 
	 * it's translated to Query
	 * 
	 * @param fullString
	 * @return
	 * @throws IOException
	 * @throws org.apache.lucene.queryparser.classic.ParseException
	 * @throws QueryNodeException
	 * @throws InvalidActivityException - now indexing
	 * @throws IOException 
	 * @throws IndexException 
	 */
	public TopDocs search(String fullString) throws  QueryNodeException, IOException, IndexException
			{
		if(INDEX_WRITE_STATE.START == writeState)
			throw new IndexException("now indexing");
		checkDirectoryReader();
		updateSearcher();
		// Query query = parser.parse(addWildcardString(fullString));
		// //pathString:자바* contents:자바*

		Query q1 = parser.parse(addBiWildcardString(fullString), "pathString");
		Query q2 = parser.parse(addWildcardString(fullString), "contents");

		BooleanQuery query = new BooleanQuery.Builder().add(q1, Occur.SHOULD).add(q2, Occur.SHOULD).build();
		TopDocs docs = searcher.search(query, 100);
		return docs;
	}

	/**
	 * get Document by docid
	 * 
	 * @param docid
	 * @return
	 * @throws IOException
	 */
	public Document getDocument(int docid) throws IOException {
		checkDirectoryReader();
		return searcher.doc(docid);
	}

	/**
	 * get explanation object
	 * 
	 * @param docid
	 * @param queryString
	 * @return
	 * @throws org.apache.lucene.queryparser.classic.ParseException
	 * @throws IOException
	 * @throws QueryNodeException
	 */
	public Explanation getExplanation(int docid, String queryString)
			throws org.apache.lucene.queryparser.classic.ParseException, IOException, QueryNodeException {
		checkDirectoryReader();
		Query query = getBooleanQuery(queryString);
		Explanation explanation = searcher.explain(query, docid);

		return explanation;
	}

	/**
	 * if there is no matched Field, return null.
	 * 
	 * @param docid
	 * @param queryString
	 * @return matched field name
	 * @throws IOException
	 */
	public String getMatchedField(int docid, String queryString) throws IOException {
		checkDirectoryReader();
		Document doc = getDocument(docid);
		for (IndexableField field : doc.getFields()) {
			Query query = new WildcardQuery(new Term(field.name(), addWildcardString(queryString)));
			Explanation ex = searcher.explain(query, docid);
			if (ex.isMatch()) {
				return field.name();
			}
		}
		return null;
	}

	/**
	 * highlight best summary to be returned
	 * 
	 * @param docid
	 * @param queryString
	 * @return
	 * @throws org.apache.lucene.queryparser.classic.ParseException
	 * @throws IOException
	 * @throws InvalidTokenOffsetsException
	 * @throws QueryNodeException
	 */
	public String highlight(int docid, String queryString) throws org.apache.lucene.queryparser.classic.ParseException,
			IOException, InvalidTokenOffsetsException, QueryNodeException {
		StringBuilder sb = new StringBuilder();
		Document doc = searcher.doc(docid);
		Query query = getBooleanQuery(queryString);
		SimpleHTMLFormatter htmlFormatter = new SimpleHTMLFormatter();
		Highlighter highlighter = new Highlighter(htmlFormatter, new QueryScorer(query));
		String contents = doc.get("contents");
		try (@SuppressWarnings("deprecation")
		TokenStream tokenStream = TokenSources.getAnyTokenStream(indexReader, docid, "contents", analyzer)) {
			TextFragment[] frag = highlighter.getBestTextFragments(tokenStream, contents, false, 2);// highlighter.getBestFragments(tokenStream,
			for (int j = 0; j < frag.length; j++) {
				if ((frag[j] != null) && (frag[j].getScore() > 0)) {
					sb.append(frag[j].toString());
				}
			}
		}

		if (sb.length() != 0) {
			return sb.toString();
		} else {
			String pathString = doc.get("pathString");
			try (@SuppressWarnings("deprecation")
			TokenStream tokenStream = TokenSources.getAnyTokenStream(indexReader, docid, "pathString", analyzer)) {
				TextFragment[] frag = highlighter.getBestTextFragments(tokenStream, pathString, false, 2);// highlighter.getBestFragments(tokenStream,
				for (int j = 0; j < frag.length; j++) {
					if ((frag[j] != null) && (frag[j].getScore() > 0)) {
						sb.append(frag[j].toString());
					}
				}
			}
			int length = 200 - sb.toString().length();
			sb.append(contents.substring(0, contents.length() < length ? contents.length() : length));
		}
		return sb.toString();
	}

	/**
	 * get term vectors from "contents" field
	 * 
	 * @param docId
	 * @return
	 * @throws IOException
	 */
	public Map<String, Integer> getTermFrequenciesFromContents(int docId) throws IOException {
		checkDirectoryReader();
		return getTermFrequenciesFromContents(indexReader, docId);
	}

	// private method below.

	private BooleanQuery getBooleanQuery(String fullString) throws QueryNodeException {

		Query q1 = parser.parse(addBiWildcardString(fullString), "pathString");
		Query q2 = parser.parse(addWildcardString(fullString), "contents");

		BooleanQuery query = new BooleanQuery.Builder().add(q1, Occur.SHOULD).add(q2, Occur.SHOULD).build();
		return query;
	}

	private void updateSearcher() throws IOException {
		checkDirectoryReader();
		DirectoryReader temp = DirectoryReader.openIfChanged(indexReader);
		if (temp != null)
			indexReader = temp;

		searcher = new IndexSearcher(indexReader);
	}

	private String addWildcardString(String fullString) {

		String[] partialQuery = fullString.split(" ");
		StringBuilder sb = new StringBuilder();
		for (String element : partialQuery) {
			if (sb.length() != 0)
				sb.append(' '); // space added for OR SEARHCHING
			sb.append(QueryParser.escape(element) + "*");
		}
		return sb.toString();
	}

	private String addBiWildcardString(String fullString) {

		String[] partialQuery = fullString.split(" ");
		StringBuilder sb = new StringBuilder();
		for (String element : partialQuery) {
			if (sb.length() != 0)
				sb.append(' '); // space added for OR SEARHCHING
			sb.append("*" + QueryParser.escape(element) + "*");
		}
		return sb.toString();
	}

	private Map<String, Integer> getTermFrequenciesFromContents(IndexReader reader, int docId) throws IOException {
		Terms vector = reader.getTermVector(docId, "contents");
		TermsEnum termsEnum = null;
		termsEnum = vector.iterator();
		Map<String, Integer> frequencies = new HashMap<>();
		BytesRef text = null;
		while ((text = termsEnum.next()) != null) {
			String term = text.utf8ToString();
			int freq = (int) termsEnum.totalTermFreq();
			frequencies.put(term, freq);
			// terms.add(term);
		}
		return frequencies;
	}

	private void checkIndexWriter() {
		if (writer == null) {
			throw new RuntimeException(
					"invalid state. After LuceneHandler.closeResources() or close(), you can't get instances.");
		}
	}

	private void checkDirectoryReader() {
		if (indexReader == null) {
			throw new RuntimeException(
					"invalid state. After LuceneHandler.closeResources() or close(), you can't search.");
		}

	}

	/**
	 * handyfinder object indexing API
	 * 
	 * @param list
	 * @throws IOException
	 */
	public int sizeOfindexDirectories(List<Directory> list) throws IOException {
		Size size = new Size();
		for (Directory dir : list) {
			Path tmp = Paths.get(dir.getPathString());
			if (dir.isRecusively())
				size.add(sizeOfindexDirectory(tmp, true));
			else
				size.add(sizeOfindexDirectory(tmp, false));
		}
		return size.getSize();
	}

	private class Size {
		int size = 0;

		public void add() {
			size++;
		}

		public void add(Size sizeObj) {
			this.size = this.size + sizeObj.getSize();
		}

		public int getSize() {
			return size;
		}

	}

	/**
	 * single directory indexing API
	 * 
	 * @param path
	 * @param recursively
	 * @throws IOException
	 */
	public Size sizeOfindexDirectory(Path path, boolean recursively) throws IOException {
		Size size = new Size();
		if (Files.isDirectory(path)) {
			Path rootDirectory = path;
			if (recursively) {
				Files.walkFileTree(rootDirectory, new SimpleFileVisitor<Path>() {
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						if (attrs.isRegularFile()) {
							size.add();
						}
						return FileVisitResult.CONTINUE;
					}
				});
			} else {
				Files.walkFileTree(rootDirectory, EnumSet.noneOf(FileVisitOption.class), 1,
						new SimpleFileVisitor<Path>() {
							public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
								if (attrs.isRegularFile()) {
									size.add();
								}
								return FileVisitResult.CONTINUE;
							}
						});
			}
		}
		return size;
	}

	public void deleteAllIndexesFromFileSystem() throws IOException {
		writer.deleteAll();
		writer.commit();
	}

	/**
	 * after method called, you can't get same instance.
	 * 
	 * @throws Exception
	 */
	public static void closeResources() throws IOException {
		Iterator<LuceneHandler> iter = map.values().iterator();
		while (iter.hasNext()) {
			try {
				iter.next().close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		map.clear();
	}

	@Override
	public void close() throws IOException {
		map.remove(indexWriterPath.toAbsolutePath().toString());
		writer.close();
		writer = null;
		indexReader.close();
		indexReader = null;
		dir.close();
		dir = null;
	}


	public class IndexException extends Exception{
		public IndexException() {
			super();
			// TODO Auto-generated constructor stub
		}

		public IndexException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
			super(message, cause, enableSuppression, writableStackTrace);
			// TODO Auto-generated constructor stub
		}

		public IndexException(String message, Throwable cause) {
			super(message, cause);
			// TODO Auto-generated constructor stub
		}

		public IndexException(String message) {
			super(message);
			// TODO Auto-generated constructor stub
		}

		public IndexException(Throwable cause) {
			super(cause);
			// TODO Auto-generated constructor stub
		}
		
	}
}
