package fiji.scripting;

import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;


/* An utility class to inline java code inside any script of any language.
 * The code, passed on as a String, is placed into a static method without
 * arguments and with a default Object return value. The bindings (read:
 * objects passed to itfrom the scripting language runtime, in a map), are
 * placed into static final private fields of the newly generated class.
 * Then the class is compiled. No reflection is used anywhere.
 * 
 * An example in jython:

from fiji.scripting import Weaver

nums = [1.0, 2.0, 3.0, 4.0]

w = Weaver.inline(
	"""
	double sum = 0;
	for (Double d : (java.util.List<Double>)nums) {
		sum += d;
	}
	return sum;
	""",
	{"nums" : nums})

print w.call()
 *
 *
 * It is safe to invoke the call() method numerous times. The bindings
 * will be read as given. If you want to change the content of the bindings,
 * make them arrays or collections, and retrieve (and cast) their contents
 * within the inlined java code.
 *
 * The return type is optional.
 *
 * The java-embedding approach is intended for short snippets of code,
 * but any code suitable for the code block of a static method is allowed.
 * 
 * @author Albert Cardona
 *
 */
public class Weaver {

	static private final AtomicInteger K = new AtomicInteger(0);
	static private final Map<String,Map<String,Object>> bindings = new HashMap<String,Map<String,Object>>();

	static public final Object steal(final String className, final String binding) {
		synchronized (bindings) {
			final Map<String,Object> m = bindings.get(className);
			if (null == m) {
				System.out.println("No binding '" + binding + "' for class '" + className + "'");
				return null;
			}
			final Object b = m.remove(binding);
			if (m.isEmpty()) bindings.remove(className);
			return b;
		}
	}

	static private final void put(final String className, final String binding, final Object ob) {
		if (null == binding) return;
		synchronized (bindings) {
			Map<String,Object> m = bindings.get(className);
			if (null == m) {
				m = new HashMap<String,Object>();
				bindings.put(className, m);
			}
			m.put(binding, ob);
		}
	}

	static public final <T extends Object> Callable<T> inline(final String code, final Map<String,?> bindings) throws Throwable
	{
		return inline(code, bindings, null, false);
	}
	
	static public final <T extends Object> Callable<T> inline(final String code, final Map<String,?> bindings, final boolean showJavaCode) throws Throwable
	{
		return inline(code, bindings, null, showJavaCode, new ArrayList<Class<?>>());
	}
	
	static public final <T extends Object> Callable<T> inline(final String code, final Map<String,?> bindings,
			final Class<T> returnType) throws Throwable
	{
		return inline(code, bindings, returnType, false, new ArrayList<Class<?>>());
	}
	
	static public final <T extends Object> Callable<T> inline(final String code, final Map<String,?> bindings,
			final Class<T> returnType, final List<Class<?>> imports) throws Throwable
	{
		return inline(code, bindings, returnType, false, imports);
	}
	
	static public final <T extends Object> Callable<T> inline(final String code, final Map<String,?> bindings,
			final List<Class<?>> imports) throws Throwable
	{
		return inline(code, bindings, null, false, imports);
	}
	
	static public final <T extends Object> Callable<T> inline(final String code, final Map<String,?> bindings, final Class<T> returnType, final boolean showJavaCode) throws Throwable
	{
		return inline(code, bindings, null, false, new ArrayList<Class<?>>());
	}

	static public final <T extends Object> Callable<T> inline(final String code, final Map<String,?> bindings,
			final Class<T> returnType, final boolean showJavaCode, final List<Class<?>> imports) throws Throwable
	{
		// Buffer to store the contents of the java file
		final StringBuilder sb = new StringBuilder(4096);
		// Acquire a unique number to generate a unique class name
		final int k = K.incrementAndGet();
		final String className = "weave.gen" + k;
		// Parse the return type, and ensure it's not null
		final Class<?> rt = null == returnType ? Object.class : returnType;
		// 1. Header of the java file
		sb.append("package weave;\n")
		  .append("import java.util.concurrent.Callable;\n");
		
		for (final Class<?> c : imports) {
			sb.append("import ").append(c.getName()).append(";\n");
		}
		
		sb.append("public final class gen").append(k)
		  .append(" implements Callable<").append(rt.getName())
		  .append("> {\n");
		// 2. Setup fields to represent the bindings
		for (final Map.Entry<String,?> e : bindings.entrySet()) {
			final String name = e.getKey();
			Type t = guessPublicClass(e.getValue());
			sb.append("static private final ")
			  .append(t.type).append(' ')
			  .append(name).append(" = (").append(t.cast)
			  .append(") fiji.scripting.Weaver.steal(\"")
			  .append(className).append("\" ,\"")
			  .append(name).append("\");\n");
			Weaver.put(className, name, e.getValue());

			System.out.println("binding is: " + (e.getValue() == null ? null : e.getValue().getClass()));
		}
		// 3. Method that runs the desired code
		sb.append("public final ").append(rt.getName())
		  .append(" call() { ").append(code).append("}\n}");

		if (showJavaCode) {
			showJavaCode("gen" + k + ".java", sb.toString());
		}
		
		return (Callable<T>) generate(className, sb.toString());
	}
	
	static private Object generate(final String className, final String javaCode) throws Throwable {
		// 4. Save the file to a temporary directory
		String relFilePath = "/" + className.replace('.', '/') + ".java"; // That is: "/weave/gen" + k + ".java"
		File tmpDir = new File(System.getProperty("java.io.tmpdir"));
		final File f = new File(tmpDir, relFilePath);
		if (!f.getParentFile().exists() && !f.getParentFile().mkdirs()) {
			throw new Exception("Could not create directories for " + f);
		}
		Writer writer = new FileWriter(f);
		writer.write(javaCode);
		writer.close();

		// 5. Compile the file, removing first any class file names that match
		final Pattern pclass = Pattern.compile("^" + className.substring(className.lastIndexOf('.') + 1) + ".*class$");
		for (File fc : f.getParentFile().listFiles()) {
			if (pclass.matcher(fc.getName()).matches()) {
				if (!fc.delete()) {
					System.out.println("Failed to delete file " + f.getAbsolutePath());
				}
			}
		}
		try {
			// Obsolete library
			//Refresh_Javas compiler = new Refresh_Javas();
			//OutputStream out = new IJLogOutputStream();
			//compiler.setOutputStreams(out, out);
			//compiler.compile(f.getAbsolutePath(), null);
			
			// Fails with minimaven exception
			//OutputStream out = new IJLogOutputStream();
			//new JavaEngine().compile(f, new PrintWriter(out));
			
			Compiler.Result r = Compiler.compile(f.getAbsolutePath(), Compiler.getClasspath(), tmpDir.getAbsolutePath(), null, null);
			if (!r.success) {
				System.out.println(r.errorMessage);
				return null;
			}
		} finally {
			// 6. Set the temporary files for deletion when the JVM exits
			// The .java file
/*
			f.deleteOnExit();
			// The .class files, if any
			for (File fc : f.getParentFile().listFiles()) {
				if (pclass.matcher(fc.getName()).matches()) {
					fc.deleteOnExit();
				}
			}
*/
		}
		// 7. Load the class file
		URLClassLoader loader = new URLClassLoader(new URL[]{tmpDir.toURI().toURL()}, Weaver.class.getClassLoader());

		try {
			final Class<?> outer = loader.loadClass(className);
			// Find and load subclasses, if any
			final String simpleName = className.substring(6); // Without "weave." package name
			for (final String subclassFilename : new File(tmpDir.getAbsolutePath() + "/weave/").list(new FilenameFilter() {
				@Override
				public final boolean accept(final File dir, final String name) {
					return name.startsWith(simpleName)
					   && '$' == name.charAt(simpleName.length())
					   && name.endsWith(".class");
				}
			})) {
				final String subclassName = "weave." + subclassFilename.substring(0, subclassFilename.length() - 6); // minus ".class"
				loader.loadClass(subclassName);
			}
			
			return outer.newInstance();
		} catch (Throwable t) {
			// If in Fiji/ImageJ, show the exception in a text window:
			try {
				Weaver.class.getClassLoader()
				  .loadClass("ij.IJ")
				  .getMethod("handleException", new Class[]{Throwable.class})
				  .invoke(null, new Object[]{t});
			} catch (Throwable tt) {
				tt.printStackTrace();
			}
			// ... and re-throw it to stop the script execution
			throw t;
		} finally {
			loader.close();
		}
	}
	
	public static Object method(final String method) throws Throwable {
		return method(method, new ArrayList<>(), false);
	}
	
	public static Object method(final String method, final List<Class<?>> imports) throws Throwable {
		return method(method, imports, false);
	}
	
	/**
	 * Define a method or methods, have an instance of an object implementing the method(s) returned to you.
	 * Declare the method(s) as static for simplicity, and use the list of imports to simplify the code.
	 * No bindings are defined; instead, pass objects directly to the method(s) arguments.
	 * 
	 * @param method The declaration(s) of the java method(s) to implement.
	 * @param imports The list of imports to add to the .java file.
	 * @param showJavaCode Whether or not to show the generated code in a Script Editor window.
	 * @return An instance of an object with the desired method(s).
	 * @throws Throwable
	 */
	public static Object method(final String method, final List<Class<?>> imports, final boolean showJavaCode) throws Throwable {
		// Buffer to store the contents of the java file
		final StringBuilder sb = new StringBuilder(4096);
		// Acquire a unique number to generate a unique class name
		final int k = K.incrementAndGet();
		final String className = "weave.gen" + k;
		// Header
		sb.append("package weave;\n\n");
		// Imports
		for (final Class<?> c : imports) {
			sb.append("import ").append(c.getName()).append(";\n");
		}
		// Class declaration
		sb.append("\npublic final class ").append("gen" + k).append(" {\n");
		// Method: the provided code
		sb.append(method);
		// Closing
		sb.append("\n}");
		
		if (showJavaCode) {
			showJavaCode("gen" + k + ".java", sb.toString());
		}

		return generate(className, sb.toString());
	}

	/**
	 * If running from Fiji/ImageJ, tries to show the generated java code
	 * in a Script Editor window, otherwise prints it to the stdout.
	 */
	private static void showJavaCode(final String filename, final String code) {
		try {			
			// Do this, but without depending on Fiji/ImageJ:

			//final Context context = (Context) IJ.runPlugIn(Context.class.getName(), "");
			//TextEditor ted = new TextEditor(context);
			//ted.createNewDocument("gen" + k + ".java", sb.toString());
			//ted.setVisible(true);

			ClassLoader loader = Weaver.class.getClassLoader();
			try {
				Class<?> context_class = loader.loadClass("org.scijava.Context");
				Method runPlugIn = loader.loadClass("ij.IJ").getMethod("runPlugIn", new Class[] {String.class, String.class});
				Object context = runPlugIn.invoke(null, new Object[]{"org.scijava.Context", ""});
				
				Class<?> editor_class = loader.loadClass("org.scijava.ui.swing.script.TextEditor");
				Constructor<?> editor_constructor = editor_class.getConstructor(new Class[]{context_class});
				Object editor = editor_constructor.newInstance(new Object[]{context});
				Method editor_cnd = editor_class.getMethod("createNewDocument", new Class[]{String.class, String.class});
				editor_cnd.invoke(editor, new Object[]{filename, code});
				Method editor_sv = editor_class.getMethod("setVisible", new Class[]{Boolean.TYPE});
				editor_sv.invoke(editor, new Object[]{true});
			} catch (Exception e) {
				e.printStackTrace();
				// In case of failure (not being in Fiji/ImageJ) print code to stdout
				System.out.println("###### Weaver.inline GENERATED CODE #####");
				System.out.println(code);
				System.out.println("###### END #####");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	static private class Type {
		final String type, cast;
		private Type(String type, String cast) {
			this.type = type;
			this.cast = cast;
		}
		private Type(String type) {
			this(type, type);
		}
	}

	/** Return the most specific yet public Class of the class hierarchy of {@param ob}. */
	static private final Type guessPublicClass(final Object ob) {
		if (null == ob) return new Type("Object");
		Class<?> c = ob.getClass();
		// Avoid boxing/unboxing: is done only once
		if (Long.class == c) return new Type("long", "Long");
		if (Double.class == c) return new Type("double", "Double");
		if (Float.class == c) return new Type("float", "Float");
		if (Byte.class == c) return new Type("byte", "Byte");
		if (Short.class == c) return new Type("short", "Short");
		if (Integer.class == c) return new Type("int", "Integer");
		if (Character.class == c) return new Type("char", "Character");

		// While not named class and not public, inspect its super class:
		while (c.isAnonymousClass() || 0 == (c.getModifiers() | Modifier.PUBLIC)) {
			c = c.getSuperclass();
		}

		// If it's an array, inspect if it's native, or fix the name
		if (c.isArray()) {
			String s = c.getSimpleName();
			// native array?
			if (s.toLowerCase().equals(s)) {
				Pattern pat = Pattern.compile("^(\\[\\])+$");
				for (String name : new String[]{"byte", "char", "short", "int", "long", "float", "double"}) {
					if (s.startsWith(name) && pat.matcher(s.substring(name.length())).matches()) {
						return new Type(s);
					}
				}
			}
			// Not native: transform "[[Ljava.util.List;" into "java.util.List[][]"
			String name = c.getName();
			int nBrackets = name.indexOf('L');
			StringBuilder sb = new StringBuilder(32);
			sb.append(name, nBrackets+1, name.length() -1); // +1 to skip 'L'
			for (int i=0; i<nBrackets; i++) sb.append('[').append(']');
			return new Type(sb.toString());
		}
		return new Type(c.getName());
	}
}
