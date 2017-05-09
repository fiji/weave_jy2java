package fiji.scripting;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;


/**
 * A compiler for .java files, extracted by refactoring the Refresh_Javas class from Johannes Schindelin.
 * Note the implicit dependency on com.sun.tools.javac.Main class included in the tools.jar.
 * 
 * @author Albert Cardona, Johannes Schindelin
 *
 */
public class Compiler {
	
	/**
	 * Modified from Johannes Schindelin's Refresh_Javas.getPluginsClassPath method.
	 * 
	 * Obtain the complete classpath for Fiji or ImageJ, which includes
	 * the basic java classpath plus, in addition, the jar files for
	 * all plugins and for all included libraries in the /jars folder.
	 * When called from outside ImageJ or Fiji, it returns the basic java classpath.
	 */
	public static String getClasspath() {
		String classPath = System.getProperty("java.class.path");

		if (null == classPath) {
			return "";
		}

		final String pluginsPath = System.getProperty("ij.dir") + "/plugins";
		final String jarsPath = System.getProperty("ij.dir") + "/jars";

		// Why? This seems inappropriate here
		/*
		// Strip out all plugin .jar files (to keep classPath short)
		if (new File(pluginsPath).exists()) {
			for (int i = 0; i >= 0; i = classPath.indexOf(File.pathSeparator, i + 1)) {
				while (classPath.substring(i).startsWith(pluginsPath)) {
					int j = classPath.indexOf(File.pathSeparator, i + 1);
					classPath = classPath.substring(0, i) + (j < 0 ? "" : classPath.substring(j + 1));
				}
			}
		}
		*/

		// Append both the plugin and library .jar files from the /plugins and /jars folder if it exists.
		try {
			if (new File(pluginsPath).exists()) {
				classPath = appendToPath(classPath, discoverJars(pluginsPath));
			}
			if (new File(jarsPath).exists()) {
				classPath = appendToPath(classPath, discoverJars(jarsPath));
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		return classPath;
	}

	public static String appendToPath(String path, String append) {
		if (append != null && !path.equals("")) {
			if (!path.equals(""))
				path += File.pathSeparator;
			return path + append;
		}
		return path;
	}

	/**
	 * Copied from Johannes Schindelin's Refresh_Javas.discoverJars method.
	 */
	public static String discoverJars(String path) throws IOException {
		if (path.equals(".rsrc") || path.endsWith("/.rsrc"))
			return "";
		if (path.endsWith(File.separator))
			path = path.substring(0, path.length() - 1);
		File file = new File(path);
		if (file.isDirectory()) {
			String result = "";
			String[] paths = file.list();
			//Arrays.sort(paths); // No need to sort
			for (int i = 0; i < paths.length; i++) {
				String add = discoverJars(path + File.separator + paths[i]);
				if (add == null || add.equals(""))
					continue;
				if (!result.equals(""))
					result += File.pathSeparator;
				result += add;
			}
			return result;
		} else if (path.endsWith(".jar"))
			return path;
		return null;
	}
	
	/**
	 * Copied from Johannes Schindelin's Refresh_Javas.getSourceRootDirectory method.
	 */
	public static File getSourceRootDirectory(String path) throws IOException {
		String packageName = getPackageName(path);
		File directory = new File(path).getCanonicalFile().getParentFile();
		if (packageName != null) {
			int dot = -1;
			do {
				directory = directory.getParentFile();
				dot = packageName.indexOf('.', dot + 1);
			} while (dot > 0);
		}
		return directory;
	}
	
	/**
	 * Copied from Johannes Schindelin's Refresh_Javas.getPackageName method.
	 */
	public static String getPackageName(String path) throws IOException {
		InputStream in = new FileInputStream(path);
		InputStreamReader streamReader = new InputStreamReader(in);
		BufferedReader reader = new BufferedReader(streamReader);

		boolean multiLineComment = false;
		String line;
		while ((line = reader.readLine()) != null) {
			if (multiLineComment) {
				int endOfComment = line.indexOf("*/");
				if (endOfComment < 0)
					continue;
				line = line.substring(endOfComment + 2);
				multiLineComment = false;
			}
			line = line.trim();
			while (line.startsWith("/*")) {
				int endOfComment = line.indexOf("*/", 2);
				if (endOfComment < 0) {
					multiLineComment = true;
					break;
				}
				line = line.substring(endOfComment + 2).trim();
			}
			if (multiLineComment)
				continue;
			if (line.startsWith("package ")) {
				int endOfPackage = line.indexOf(';');
				if (endOfPackage < 0)
					break;
				in.close();
				reader.close();
				return line.substring(8, endOfPackage);
			}
			if (!line.equals("") && !line.startsWith("//")) {
				break;
			}
		}
		in.close();
		return null;
	}
	
	static public class Result {
		final boolean success;
		final String errorMessage;
		
		public Result(final boolean success, final String msg) {
			this.success = success;
			this.errorMessage = msg;
		}
	}
	
	/**
	 * Borrowing very heavily from Johannes Schindelin's Refresh_Javas.compile method.
	 * 
	 * @param path The file path to the .java file to compile.
	 * @param classPath The java classpath, which for Fiji includes all .jar files under /jars and /plugins
	 *                  and can be obtained from {@link Compiler#getClasspath()}.
	 * @param rootDir Optional (can be null). The top-level root directory from which the java file is to be compiled.
	 * @param outPath Optional (can be null), the target file path for the .class file(s). Will use the same folder as the .java file when null.
	 * @param extraArgs Optional (can be null). Additional arguments for the javac compiler.
	 * 
	 * @return A new {@link Result} object indicating success or not, and if so an error message.
	 */
	static public Result compile(
			final String path,
			String classPath,
			String rootDir,
			final String outPath,
			final String[] extraArgs)
			throws ClassNotFoundException, NoSuchMethodException,
		       IllegalAccessException,
		       InvocationTargetException {
		
		String[] arguments = { "-deprecation", "-Xlint:unchecked", "-g", path };
		
		File root = null;
		try {
			root = Compiler.getSourceRootDirectory(path);
		} catch (Exception e) { /* ignore */ }
		
		// Add the folder where the .java file lives to the classpath
		if (null != root) {
			classPath = root.getPath() + (classPath.equals("") ? "" : File.pathSeparator + classPath);
		}
		if (!classPath.equals("")) {
			arguments = unshift(arguments, new String[] { "-classpath", classPath });
		}
		if (outPath != null) {
			arguments = unshift(arguments, new String[] { "-d", outPath });
		}
		final Method javac = Compiler.class
				.getClassLoader()
				.loadClass("com.sun.tools.javac.Main")
				.getMethod("compile", new Class[]{arguments.getClass(), PrintWriter.class});

		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		final Object result = javac.invoke(null, new Object[] { arguments, new PrintWriter(buffer) });

		if (result.equals(new Integer(0))) {
			return new Result(true, "");
		}
		return new Result(false, "Could not compile " + path + ":\n" + buffer.toString());
	}
	
	/**
	 * Copied from Johannes Schindelin's Refresh_Javas.unshift method.
	 * 
	 * @param list The base array.
	 * @param add The additional items to add to the base array.
	 * @return
	 */
	static protected String[] unshift(final String[] list, final String[] add) {
		final String[] result = new String[list.length + add.length];
		System.arraycopy(add, 0, result, 0, add.length);
		System.arraycopy(list, 0, result, add.length, list.length);
		return result;
	}

}
