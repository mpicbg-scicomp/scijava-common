/*
 * #%L
 * SciJava Common shared library for SciJava software.
 * %%
 * Copyright (C) 2009 - 2016 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.script;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.ScriptException;

import org.scijava.Context;
import org.scijava.Contextual;
import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.NullContextException;
import org.scijava.command.Command;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogService;
import org.scijava.module.AbstractModuleInfo;
import org.scijava.module.DefaultMutableModuleItem;
import org.scijava.module.ModuleException;
import org.scijava.plugin.Parameter;
import org.scijava.util.DigestUtils;
import org.scijava.util.FileUtils;

/**
 * Metadata about a script.
 * <p>
 * This class is responsible for parsing the script for parameters. See
 * {@link #parseParameters()} for details.
 * </p>
 * 
 * @author Curtis Rueden
 * @author Johannes Schindelin
 */
public class ScriptInfo extends AbstractModuleInfo implements Contextual {

	private static final int PARAM_CHAR_MAX = 640 * 1024; // should be enough ;-)

	private final String path;
	private final String script;

	@Parameter
	private Context context;

	@Parameter
	private LogService log;

	@Parameter
	private ScriptService scriptService;

	@Parameter
	private ConvertService convertService;

	/** True iff the return value is explicitly declared as an output. */
	private boolean returnValueDeclared;

	/**
	 * Creates a script metadata object which describes the given script file.
	 * 
	 * @param context The SciJava application context to use when populating
	 *          service inputs.
	 * @param file The script file.
	 */
	public ScriptInfo(final Context context, final File file) {
		this(context, file.getPath());
	}

	/**
	 * Creates a script metadata object which describes the given script file.
	 * 
	 * @param context The SciJava application context to use when populating
	 *          service inputs.
	 * @param path Path to the script file.
	 */
	public ScriptInfo(final Context context, final String path) {
		this(context, path, null);
	}

	/**
	 * Creates a script metadata object which describes a script provided by the
	 * given {@link Reader}.
	 * 
	 * @param context The SciJava application context to use when populating
	 *          service inputs.
	 * @param path Pseudo-path to the script file. This file does not actually
	 *          need to exist, but rather provides a name for the script with file
	 *          extension.
	 * @param reader Reader which provides the script itself (i.e., its contents).
	 */
	public ScriptInfo(final Context context, final String path,
		final Reader reader)
	{
		setContext(context);
		this.path = path;

		String script = null;
		if (reader != null) {
			try {
				script = getReaderContentsAsString(reader);
			}
			catch (final IOException exc) {
				log.error("Error reading script: " + path, exc);
			}
		}
		this.script = script;
	}

	// -- ScriptInfo methods --

	/**
	 * Gets the path to the script on disk.
	 * <p>
	 * If the path doesn't actually exist on disk, then this is a pseudo-path
	 * merely for the purpose of naming the script with a file extension, and the
	 * actual script content is delivered by the {@link BufferedReader} given by
	 * {@link #getReader()}.
	 * </p>
	 */
	public String getPath() {
		return path;
	}

	/**
	 * Gets a reader which delivers the script's content.
	 * <p>
	 * This might be null, in which case the content is stored in a file on disk
	 * given by {@link #getPath()}.
	 * </p>
	 */
	public BufferedReader getReader() {
		if (script == null) {
			return null;
		}
		return new BufferedReader(new StringReader(script), PARAM_CHAR_MAX);
	}

	/**
	 * Parses the script's input and output parameters from the script header.
	 * <p>
	 * This method is called automatically the first time any parameter accessor
	 * method is called ({@link #getInput}, {@link #getOutput}, {@link #inputs()},
	 * {@link #outputs()}, etc.). Subsequent calls will reparse the parameters.
	 * <p>
	 * SciJava's scripting framework supports specifying @{@link Parameter}-style
	 * inputs and outputs in a preamble. The format is a simplified version of the
	 * Java @{@link Parameter} annotation syntax. The following syntaxes are
	 * supported:
	 * </p>
	 * <ul>
	 * <li>{@code // @<type> <varName>}</li>
	 * <li>{@code // @<type>(<attr1>=<value1>, ..., <attrN>=<valueN>) <varName>}</li>
	 * <li>{@code // @<IOType> <type> <varName>}</li>
	 * <li>
	 * {@code // @<IOType>(<attr1>=<value1>, ..., <attrN>=<valueN>) <type> <varName>}
	 * </li>
	 * </ul>
	 * <p>
	 * Where:
	 * </p>
	 * <ul>
	 * <li>{@code //} = the comment style of the scripting language, so that the
	 * parameter line is ignored by the script engine itself.</li>
	 * <li>{@code <IOType>} = one of {@code INPUT}, {@code OUTPUT}, or
	 * {@code BOTH}.</li>
	 * <li>{@code <varName>} = the name of the input or output variable.</li>
	 * <li>{@code <type>} = the Java {@link Class} of the variable.</li>
	 * <li>{@code <attr*>} = an attribute key.</li>
	 * <li>{@code <value*>} = an attribute value.</li>
	 * </ul>
	 * <p>
	 * See the @{@link Parameter} annotation for a list of valid attributes.
	 * </p>
	 * <p>
	 * Here are a few examples:
	 * </p>
	 * <ul>
	 * <li>{@code // @Dataset dataset}</li>
	 * <li>{@code // @double(type=OUTPUT) result}</li>
	 * <li>{@code // @BOTH ImageDisplay display}</li>
	 * <li>{@code // @INPUT(persist=false, visibility=INVISIBLE) boolean verbose}</li>
	 * parameters will be parsed and filled just like @{@link Parameter}
	 * -annotated fields in {@link Command}s.
	 * </ul>
	 */
	// NB: Widened visibility from AbstractModuleInfo.
	@Override
	public void parseParameters() {
		clearParameters();
		returnValueDeclared = false;

		try {
			final BufferedReader in;
			if (script == null) {
				in = new BufferedReader(new FileReader(getPath()));
			}
			else {
				in = getReader();
			}
			while (true) {
				final String line = in.readLine();
				if (line == null) break;

				// NB: Scan for lines containing an '@' with no prior alphameric
				// characters. This assumes that only non-alphanumeric characters can
				// be used as comment line markers.
				if (line.matches("^[^\\w]*@.*")) {
					final int at = line.indexOf('@');
					parseParam(line.substring(at + 1));
				}
				else if (line.matches(".*\\w.*")) break;
			}
			in.close();

			if (!returnValueDeclared) addReturnValue();
		}
		catch (final IOException exc) {
			log.error("Error reading script: " + path, exc);
		}
		catch (final ScriptException exc) {
			log.error("Invalid parameter syntax for script: " + path, exc);
		}
	}

	/** Gets whether the return value is explicitly declared as an output. */
	public boolean isReturnValueDeclared() {
		return returnValueDeclared;
	}

	// -- ModuleInfo methods --

	@Override
	public String getDelegateClassName() {
		return ScriptModule.class.getName();
	}

	@Override
	public Class<?> loadDelegateClass() {
		return ScriptModule.class;
	}

	@Override
	public ScriptModule createModule() throws ModuleException {
		return new ScriptModule(this);
	}

	// -- Contextual methods --

	@Override
	public Context context() {
		if (context == null) throw new NullContextException();
		return context;
	}

	@Override
	public Context getContext() {
		return context;
	}

	@Override
	public void setContext(final Context context) {
		context.inject(this);
	}

	// -- Identifiable methods --

	@Override
	public String getIdentifier() {
		return "script:" + path;
	}

	// -- Locatable methods --

	@Override
	public String getLocation() {
		return new File(path).toURI().normalize().toString();
	}

	@Override
	public String getVersion() {
		final File file = new File(path);
		if (!file.exists()) return null; // no version for non-existent script
		final Date lastModified = FileUtils.getModifiedTime(file);
		final String datestamp =
			new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss").format(lastModified);
		try {
			final String hash = DigestUtils.bestHex(FileUtils.readFile(file));
			return datestamp + "-" + hash;
		}
		catch (final IOException exc) {
			log.error(exc);
		}
		return datestamp;
	}

	// -- Helper methods --

	private void parseParam(final String param) throws ScriptException {
		final int lParen = param.indexOf("(");
		final int rParen = param.lastIndexOf(")");
		if (rParen < lParen) {
			throw new ScriptException("Invalid parameter: " + param);
		}
		if (lParen < 0) parseParam(param, parseAttrs(""));
		else {
			final String cutParam =
				param.substring(0, lParen) + param.substring(rParen + 1);
			final String attrs = param.substring(lParen + 1, rParen);
			parseParam(cutParam, parseAttrs(attrs));
		}
	}

	private void parseParam(final String param,
		final HashMap<String, String> attrs) throws ScriptException
	{
		final String[] tokens = param.trim().split("[ \t\n]+");
		checkValid(tokens.length >= 1, param);
		final String typeName, varName;
		if (isIOType(tokens[0])) {
			// assume syntax: <IOType> <type> <varName>
			checkValid(tokens.length >= 3, param);
			attrs.put("type", tokens[0]);
			typeName = tokens[1];
			varName = tokens[2];
		}
		else {
			// assume syntax: <type> <varName>
			checkValid(tokens.length >= 2, param);
			typeName = tokens[0];
			varName = tokens[1];
		}
		final Class<?> type = scriptService.lookupClass(typeName);
		addItem(varName, type, attrs);
		if (ScriptModule.RETURN_VALUE.equals(varName)) returnValueDeclared = true;
	}

//	/** Parses a comma-delimited list of {@code key=value} pairs into a map. */
//	private HashMap<String, String> parseAttrs(final String attrs)
//		throws ScriptException
//	{
//		// TODO: We probably want to use a real CSV parser.
//		final HashMap<String, String> attrsMap = new HashMap<String, String>();
//		for (final String token : attrs.split(",")) {
//			if (token.isEmpty()) continue;
//			final int equals = token.indexOf("=");
//			if (equals < 0) throw new ScriptException("Invalid attribute: " + token);
//			final String key = token.substring(0, equals).trim();
//			String value = token.substring(equals + 1).trim();
//			if (value.startsWith("\"") && value.endsWith("\"")) {
//				value = value.substring(1, value.length() - 1);
//			}
//			attrsMap.put(key, value);
//		}
//		return attrsMap;
//	}

	private HashMap<String, String> parseAttrs(final String attrs)
			throws ScriptException
	{
		Pattern pattern = Pattern.compile("([^,=\\s]+)\\s*?=\\s*?(\"[^\"]+\"|'[^']+'|\\{[^\\}]+\\}|[^\\W]\\w*)");

		final HashMap<String, String> attrsMap = new HashMap<String, String>();
		Matcher matcher = pattern.matcher(attrs);
		while (matcher.find())
		{
			if( matcher.group( 1 ) == null || matcher.group( 2 ) == null )
				throw new ScriptException("Invalid key/attribute: " + matcher.group( 1 ) + ":" + matcher.group( 2 ));

			String key = matcher.group( 1 );
			if(attrsMap.containsKey( key ))
				throw new ScriptException("Duplicate key: " + key );

			String value = matcher.group( 2 );

			if(!key.equals( "choices" ))
				value = value.replaceAll( "\"|'", "" );

			attrsMap.put(key, value);
		}

		return attrsMap;
	}

	private <T> List<T> parseChoices(final DefaultMutableModuleItem<T> item, final String choices)
			throws ScriptException
	{
		Pattern pattern = Pattern.compile("(\"[^\"]+\"|'[^']+'|[^,\\s\\{\\}]+)");

		final ArrayList<T> arrayList = new ArrayList<T>();
		Matcher matcher = pattern.matcher(choices);
		while (matcher.find())
		{
			if( matcher.group( 0 ) == null )
				throw new ScriptException( "Invalid attribute: " + matcher.group( 0 ) );

			String value = matcher.group( 0 ).replaceAll( "\"|'", "" );
			arrayList.add( convertService.convert( value, item.getType() ) );
		}

		return arrayList;
	}

	private boolean isIOType(final String token) {
		return convertService.convert(token, ItemIO.class) != null;
	}

	private void checkValid(final boolean valid, final String param)
		throws ScriptException
	{
		if (!valid) throw new ScriptException("Invalid parameter: " + param);
	}

	/** Adds an output for the value returned by the script itself. */
	private void addReturnValue() throws ScriptException {
		final HashMap<String, String> attrs = new HashMap<String, String>();
		attrs.put("type", "OUTPUT");
		addItem(ScriptModule.RETURN_VALUE, Object.class, attrs);
	}

	private <T> void addItem(final String name, final Class<T> type,
		final Map<String, String> attrs) throws ScriptException
	{
		final DefaultMutableModuleItem<T> item =
			new DefaultMutableModuleItem<T>(this, name, type);
		for (final String key : attrs.keySet()) {
			final String value = attrs.get(key);
			assignAttribute(item, key, value);
		}
		if (item.isInput()) registerInput(item);
		if (item.isOutput()) registerOutput(item);
	}

	private <T> void assignAttribute(final DefaultMutableModuleItem<T> item,
		final String key, final String value) throws ScriptException
	{
		// CTR: There must be an easier way to do this.
		// Just compile the thing using javac? Or parse via javascript, maybe?
		if ("callback".equalsIgnoreCase(key)) {
			item.setCallback(value);
		}
		else if ("choices".equalsIgnoreCase(key)) {
			item.setChoices( parseChoices( item, value ) );
		}
		else if ("columns".equalsIgnoreCase(key)) {
			item.setColumnCount(convertService.convert(value, int.class));
		}
		else if ("description".equalsIgnoreCase(key)) {
			item.setDescription(value);
		}
		else if ("initializer".equalsIgnoreCase(key)) {
			item.setInitializer(value);
		}
		else if ("type".equalsIgnoreCase(key)) {
			item.setIOType(convertService.convert(value, ItemIO.class));
		}
		else if ("label".equalsIgnoreCase(key)) {
			item.setLabel(value);
		}
		else if ("max".equalsIgnoreCase(key)) {
			item.setMaximumValue(convertService.convert(value, item.getType()));
		}
		else if ("min".equalsIgnoreCase(key)) {
			item.setMinimumValue(convertService.convert(value, item.getType()));
		}
		else if ("name".equalsIgnoreCase(key)) {
			item.setName(value);
		}
		else if ("persist".equalsIgnoreCase(key)) {
			item.setPersisted(convertService.convert(value, boolean.class));
		}
		else if ("persistKey".equalsIgnoreCase(key)) {
			item.setPersistKey(value);
		}
		else if ("required".equalsIgnoreCase(key)) {
			item.setRequired(convertService.convert(value, boolean.class));
		}
		else if ("softMax".equalsIgnoreCase(key)) {
			item.setSoftMaximum(convertService.convert(value, item.getType()));
		}
		else if ("softMin".equalsIgnoreCase(key)) {
			item.setSoftMinimum(convertService.convert(value, item.getType()));
		}
		else if ("stepSize".equalsIgnoreCase(key)) {
			try {
				final double stepSize = Double.parseDouble(value);
				item.setStepSize(stepSize);
			}
			catch (final NumberFormatException exc) {
				log.warn("Script parameter " + item.getName() +
					" has an invalid stepSize: " + value);
			}
		}
		else if ("style".equalsIgnoreCase(key)) {
			item.setWidgetStyle(value);
		}
		else if ("visibility".equalsIgnoreCase(key)) {
			item.setVisibility(convertService.convert(value, ItemVisibility.class));
		}
		else if ("value".equalsIgnoreCase(key)) {
			item.setDefaultValue(convertService.convert(value, item.getType()));
		}
		else {
			throw new ScriptException("Invalid attribute name: " + key);
		}
	}

	/**
	 * Read entire contents of a Reader and return as String.
	 *
	 * @param reader {@link Reader} whose contents should be returned as String.
	 *          Expected to never be <code>null</code>.
	 * @return contents of reader as String.
	 * @throws IOException If an I/O error occurs
	 * @throws NullPointerException If reader is <code>null</code>
	 */
	private static String getReaderContentsAsString(final Reader reader)
		throws IOException, NullPointerException
	{
		final char[] buffer = new char[8192];
		final StringBuilder builder = new StringBuilder();

		int read;
		while ((read = reader.read(buffer)) != -1) {
			builder.append(buffer, 0, read);
		}

		return builder.toString();
	}

}
