/*******************************************************************************
 * Copyright (c) 2000, 2026 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.swt.internal;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.jar.*;

public class Library {

	/* SWT Version - Mmmm (M=major, mmm=minor) */

	/**
	 * SWT Major version number (must be >= 0)
	 */
	static int MAJOR_VERSION = 4;

	/**
	 * SWT Minor version number (must be in the range 0..999)
	 */
	static int MINOR_VERSION = 974;

	/**
	 * SWT revision number (must be >= 0)
	 */
	static int REVISION = 101;

	/**
	 * The JAVA and SWT versions
	 */
	public static final int JAVA_VERSION, SWT_VERSION;
	public static final String USER_HOME;

	static final String SEPARATOR;
	static final String DELIMITER;

	static final String JAVA_LIB_PATH = "java.library.path";
	static final String SWT_LIB_PATH = "swt.library.path";

	static final String SUFFIX_64 = "-64";	//$NON-NLS-1$
	static final String SWT_LIB_DIR;

	/**
	 * When true (-Dswt.library.verbose=true), the extract directory, every load
	 * candidate and its outcome are logged to stderr.
	 */
	static final boolean VERBOSE = Boolean.getBoolean ("swt.library.verbose"); //$NON-NLS-1$

	static final int PUBLISH_ATTEMPTS = 5;
	static final long PUBLISH_INITIAL_BACKOFF_MS = 50;

static {
	DELIMITER = System.lineSeparator();
	SEPARATOR = File.separator;
	USER_HOME = System.getProperty ("user.home");
	SWT_LIB_DIR = ".swt" + SEPARATOR + "lib" + SEPARATOR + os() + SEPARATOR + arch(); //$NON-NLS-1$ $NON-NLS-2$
	JAVA_VERSION = parseVersion(System.getProperty("java.version")); //$NON-NLS-1$
	SWT_VERSION = SWT_VERSION(MAJOR_VERSION, MINOR_VERSION);
}

static String arch() {
	String osArch = System.getProperty("os.arch"); //$NON-NLS-1$
	if (osArch.equals ("amd64")) return "x86_64"; //$NON-NLS-1$ $NON-NLS-2$
	return osArch;
}

static String os() {
	String osName = System.getProperty("os.name"); //$NON-NLS-1$
	if (osName.equals ("Linux")) return "linux"; //$NON-NLS-1$ $NON-NLS-2$
	if (osName.equals ("Mac OS X")) return "macosx"; //$NON-NLS-1$ $NON-NLS-2$
	if (osName.startsWith ("Win")) return "win32"; //$NON-NLS-1$ $NON-NLS-2$
	return osName;
}

static void chmod(String permision, String path) {
	if (os().equals ("win32")) return; //$NON-NLS-1$
	try {
		Runtime.getRuntime ().exec (new String []{"chmod", permision, path}).waitFor(); //$NON-NLS-1$
	} catch (Throwable e) {
		try {
			new File(path).setExecutable(true);
		} catch (Throwable e1) {
		}
	}
}

/* Use method instead of in-lined constants to avoid compiler warnings */
static long longConst() {
	return 0x1FFFFFFFFL;
}

static int parseVersion(String version) {
	if (version == null) return 0;
	int major = 0, minor = 0, micro = 0;
	int length = version.length(), index = 0, start = 0;
	while (index < length && Character.isDigit(version.charAt(index))) index++;
	try {
		if (start < length) major = Integer.parseInt(version.substring(start, index));
	} catch (NumberFormatException e) {}
	start = ++index;
	while (index < length && Character.isDigit(version.charAt(index))) index++;
	try {
		if (start < length) minor = Integer.parseInt(version.substring(start, index));
	} catch (NumberFormatException e) {}
	start = ++index;
	while (index < length && Character.isDigit(version.charAt(index))) index++;
	try {
		if (start < length) micro = Integer.parseInt(version.substring(start, index));
	} catch (NumberFormatException e) {}
	return JAVA_VERSION(major, minor, micro);
}

/**
 * Returns the Java version number as an integer.
 *
 * @return the version
 */
public static int JAVA_VERSION (int major, int minor, int micro) {
	return (major << 16) + (minor << 8) + micro;
}

/**
 * Returns the SWT version number as an integer.
 *
 * @return the version
 */
public static int SWT_VERSION (int major, int minor) {
	return major * 1000 + minor;
}

static void verbose (String text) {
	if (VERBOSE) System.err.println ("SWT: " + text); //$NON-NLS-1$
}

static void appendReason (StringBuilder message, String reason) {
	if (message.length() == 0) message.append(DELIMITER);
	message.append('\t');
	message.append(reason);
	message.append(DELIMITER);
}

/**
 * Writes the resource into the existing <code>outFile</code>. The file is opened
 * for writing rather than replaced, because a replace needs DELETE access, which
 * is exactly what a virus scanner or indexer holding the file open takes away.
 *
 * @return null upon success, otherwise the reason for the failure
 */
private static String extractResource(String resourceName, File outFile) {
	try (InputStream inputStream = Library.class.getResourceAsStream (resourceName)) {
		if (inputStream == null) return "resource not found: " + resourceName; //$NON-NLS-1$
		try (OutputStream outputStream = Files.newOutputStream (outFile.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			inputStream.transferTo (outputStream);
		}
	} catch (Throwable e) {
		return e.toString();
	}
	return null;
}

static void deleteOrDeleteOnExit (File file) {
	if (!file.delete () && file.exists ()) file.deleteOnExit ();
}

static boolean sleepBeforeRetry (long millis) {
	try {
		Thread.sleep (millis);
		return true;
	} catch (InterruptedException e) {
		Thread.currentThread ().interrupt ();
		return false;
	}
}

/**
 * Moves the fully written <code>tempFile</code> to <code>file</code>. The rename is
 * retried briefly, because another process (virus scanner, indexer, cloud sync,
 * backup agent) often holds a handle on a freshly written executable for a few
 * milliseconds. If the rename never succeeds, the content is copied instead: a
 * copy only needs to read the source, while a rename needs to unlink it, which
 * is the right such a handle denies.
 *
 * @return null upon success, otherwise the reason for the failure
 */
static String publish (File tempFile, File file) {
	Path source = tempFile.toPath (), target = file.toPath ();
	Throwable lastError = null;
	long backoff = PUBLISH_INITIAL_BACKOFF_MS;
	for (int attempt = 1; attempt <= PUBLISH_ATTEMPTS; attempt++) {
		try {
			Files.move (source, target);
			return null;
		} catch (Throwable e) {
			lastError = e;
		}
		/* Another process published while we were extracting. Windows may
		 * report that as access denied rather than as an existing file. */
		if (lastError instanceof FileAlreadyExistsException || file.exists ()) {
			deleteOrDeleteOnExit (tempFile);
			return null;
		}
		if (attempt == PUBLISH_ATTEMPTS || !sleepBeforeRetry (backoff)) break;
		backoff *= 2;
	}

	/* No REPLACE_EXISTING: anything at the target was published by someone
	 * else, and a partial file left by a failed copy is ours to remove. */
	try {
		Files.copy (source, target);
	} catch (FileAlreadyExistsException e) {
		deleteOrDeleteOnExit (tempFile);
		return null;
	} catch (Throwable e) {
		file.delete ();
		return "rename failed: " + lastError + "; copy failed: " + e; //$NON-NLS-1$ //$NON-NLS-2$
	}
	chmod ("755", file.getPath ()); //$NON-NLS-1$
	deleteOrDeleteOnExit (tempFile);
	System.err.println ("SWT: published " + file + " by copy, rename failed: " + lastError); //$NON-NLS-1$ //$NON-NLS-2$
	return null;
}

static boolean extractFailed (StringBuilder message, File file, File tempFile, String reason) {
	String text = "Could not extract " + file; //$NON-NLS-1$
	if (tempFile != null) text += " from " + tempFile.getName () + " (" + tempFile.length () + " bytes)"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	text += ": " + reason; //$NON-NLS-1$
	System.err.println ("SWT: " + text); //$NON-NLS-1$
	appendReason (message, text);
	return false;
}

/**
 * Extract file with 'mappedName' into path 'extractToFilePath'.
 * Does not overwrite existing file.
 * Does not leave trash on error.
 * @param extractToFilePath full path of where the file is to be extacted to, inc name of file,
 *                          e.g /home/USER/.swt/lib/linux/x86_64/libswt-MYLIB-gtk-4826.so
 * @param mappedName file to be searched in jar.
 * @param message receives the reason when extraction fails.
 * @return	true upon success, failure if something went wrong.
 */
static boolean extract (String extractToFilePath, String mappedName, StringBuilder message) {
	File file = new File(extractToFilePath);
	if (file.exists ()) return true;

	String resourceName = "/" + mappedName.replace('\\', '/'); //$NON-NLS-1$
	if (Library.class.getResource (resourceName) == null) {
		verbose ("not in jar: " + resourceName); //$NON-NLS-1$
		return false;
	}

	// Write to temp file first, so that other processes don't see
	// partially written library on disk
	File tempFile;
	try {
		tempFile = File.createTempFile (file.getName(), ".tmp", file.getParentFile()); //$NON-NLS-1$
	} catch (Throwable e) {
		return extractFailed (message, file, null, "cannot create temp file: " + e); //$NON-NLS-1$
	}

	String error = extractResource (resourceName, tempFile);
	if (error == null) {
		// Make it executable
		chmod ("755", tempFile.getPath()); //$NON-NLS-1$
		error = publish (tempFile, file);
	}
	if (error != null) {
		boolean failed = extractFailed (message, file, tempFile, error);
		deleteOrDeleteOnExit (tempFile);
		return failed;
	}
	verbose ("extracted " + file); //$NON-NLS-1$
	return true;
}

static boolean isLoadable () {
	return isLoadable(false);
}

static boolean isLoadable (boolean ignoreMissingManifest) {
	String error = loadableError (ignoreMissingManifest);
	if (error != null) System.err.println (error);
	return error == null;
}

/**
 * @return null if the natives in this jar match the running platform, otherwise the reason they do not
 */
static String loadableError (boolean ignoreMissingManifest) {
	URL url = Platform.class.getClassLoader ().getResource ("org/eclipse/swt/internal/Library.class"); //$NON-NLS-1$
	if (!url.getProtocol ().equals ("jar")) { //$NON-NLS-1$
		/* SWT is presumably running in a development environment */
		return null;
	}

	Attributes attributes = null;
	try {
		URLConnection connection = url.openConnection();
		if (!(connection instanceof JarURLConnection jc)) {
			/* should never happen for a "jar:" url */
			return "SWT jar cannot be opened: " + url; //$NON-NLS-1$
		}
		attributes = jc.getMainAttributes();
	} catch (IOException e) {
		/* should never happen for a valid SWT jar with the expected manifest values */
		return "SWT jar manifest cannot be read: " + e; //$NON-NLS-1$
	}

	String os = os ();
	String arch = arch ();
	String manifestOS = attributes.getValue ("SWT-OS"); //$NON-NLS-1$
	String manifestArch = attributes.getValue ("SWT-Arch"); //$NON-NLS-1$

	// Uber JAR (neither entry) or universal macOS JAR (no SWT-Arch)
	if ((manifestOS == null || manifestArch == null) && ignoreMissingManifest) {
		return null;
	}

	if (!arch.equals (manifestArch) || !os.equals (manifestOS)) {
		return "SWT platform mismatch: manifest SWT-OS=" + manifestOS + ", SWT-Arch=" + manifestArch //$NON-NLS-1$ //$NON-NLS-2$
			+ "; runtime os=" + os + ", arch=" + arch; //$NON-NLS-1$ //$NON-NLS-2$
	}

	return null;
}

static boolean load (String libName, StringBuilder message) {
	try {
		if (libName.contains (SEPARATOR)) {
			System.load (libName);
		} else {
			System.loadLibrary (libName);
		}
		verbose ("loaded " + libName); //$NON-NLS-1$
		return true;
	} catch (UnsatisfiedLinkError e) {
		verbose ("cannot load " + libName + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		appendReason (message, e.getMessage());
	}
	return false;
}

/**
 * Loads the shared library that matches the version of the
 * Java code which is currently running.  SWT shared libraries
 * follow an encoding scheme where the major, minor and revision
 * numbers are embedded in the library name and this along with
 * <code>name</code> is used to load the library.  If this fails,
 * <code>name</code> is used in another attempt to load the library,
 * this time ignoring the SWT version encoding scheme.
 *
 * @param name the name of the library to load
 */
public static void loadLibrary (String name) {
	loadLibrary (name, true);
}

/**
 * Loads the shared library that matches the version of the
 * Java code which is currently running.  SWT shared libraries
 * follow an encoding scheme where the major, minor and revision
 * numbers are embedded in the library name and this along with
 * <code>name</code> is used to load the library.  If this fails,
 * <code>name</code> is used in another attempt to load the library,
 * this time ignoring the SWT version encoding scheme.
 *
 * @param name the name of the library to load
 * @param mapName true if the name should be mapped, false otherwise
 */
public static void loadLibrary (String name, boolean mapName) {
	String prop = System.getProperty ("sun.arch.data.model"); //$NON-NLS-1$
	if (prop == null) prop = System.getProperty ("com.ibm.vm.bitmode"); //$NON-NLS-1$
	if (prop != null) {
		if ("32".equals (prop)) { //$NON-NLS-1$
			throw new UnsatisfiedLinkError ("Cannot load 64-bit SWT libraries on 32-bit JVM"); //$NON-NLS-1$
		}
	}

	/* Compute the library name and mapped name */
	final int candidates = 3;
	String[] libNames = new String[candidates], mappedNames = new String[candidates];
	if (mapName) {
		String version = getVersionString ();
		libNames[0] = name + "-" + Platform.PLATFORM + "-" + version;  //$NON-NLS-1$ //$NON-NLS-2$
		libNames[1] = name + "-" + Platform.PLATFORM;  //$NON-NLS-1$
		libNames[2] = name;
		for (int i = 0; i < candidates; i++) {
			mappedNames[i] = mapLibraryName (libNames[i]);
		}
	} else {
		for (int i = 0; i < candidates; i++) {
			libNames[i] = mappedNames[i] = name;
		}
	}

	StringBuilder message = new StringBuilder();

	/* Try loading library from swt library path */
	String swtLibPath = System.getProperty (SWT_LIB_PATH);
	String path = swtLibPath;
	if (path != null) {
		path = new File (path).getAbsolutePath ();
		for (int i = 0; i < candidates; i++) {
			if ((i == 0 || mapName) && load (path + SEPARATOR + mappedNames[i], message)) return;
		}
	}

	/* Try loading library from java library path */
	for (int i = 0; i < candidates; i++) {
		if ((i == 0 || mapName) && load (libNames[i], message)) return;
	}

	/* Try loading library from the tmp directory if swt library path is not specified.
	 * Create the tmp folder if it doesn't exist. Tmp folder looks like this:
	 * ~/.swt/lib/<platform>/<arch>/
	 */
	String[] fileNames = new String[candidates];
	for (int i = 0; i < candidates; i++) {
		fileNames[i] = mappedNames[i];
	}
	if (path == null) {
		path = USER_HOME;
		File dir = new File (path, SWT_LIB_DIR);
		if ((dir.exists () && dir.isDirectory ()) || dir.mkdirs ()) { // Create if not exist.
			path = dir.getAbsolutePath ();
		} else {
			/* fall back to using the home dir directory */
			for (int i = 0; i < candidates; i++) {
				fileNames[i] = mapLibraryName (libNames[i] + SUFFIX_64);
			}
		}
		for (int i = 0; i < candidates; i++) {
			if ((i == 0 || mapName) && load (path + SEPARATOR + fileNames[i], message)) return;
		}
	}

	/* Try extracting and loading library from jar. */
	if (path != null) {
		verbose ("extract directory " + path); //$NON-NLS-1$
		for (int i = 0; i < candidates; i++) {
			if ((i == 0 || mapName) && extract (path + SEPARATOR + fileNames[i], mappedNames[i], message)) {
				if (load(path + SEPARATOR + fileNames[i], message)) return;
			}
		}
	}

	/* Failed to find the library */
	String loadableError = loadableError (true);
	if (loadableError != null) appendReason (message, loadableError);
	throw new UnsatisfiedLinkError ("Could not load SWT library " + name //$NON-NLS-1$
		+ " (os=" + os () + ", arch=" + arch () //$NON-NLS-1$ //$NON-NLS-2$
		+ ", " + SWT_LIB_PATH + "=" + swtLibPath //$NON-NLS-1$ //$NON-NLS-2$
		+ ", extract directory=" + path + "). Reasons: " + message.toString()); //$NON-NLS-1$ //$NON-NLS-2$
}

static String mapLibraryName (String libName) {
	return mapLibraryName(libName, true);
}

static String mapLibraryName (String libName, boolean replaceDylib) {
	/* SWT libraries in the Macintosh use the extension .jnilib but the some VMs map to .dylib. */
	libName = System.mapLibraryName (libName);
	String ext = ".dylib"; //$NON-NLS-1$
	if (libName.endsWith(ext) && replaceDylib) {
		libName = libName.substring(0, libName.length() - ext.length()) + ".jnilib"; //$NON-NLS-1$
	}
	return libName;
}

/**
 * @return String Combined SWT version like 4826
 */
public static String getVersionString () {
	String version = System.getProperty ("swt.version"); //$NON-NLS-1$
	if (version == null) {
		version = "" + MAJOR_VERSION; //$NON-NLS-1$
		/* Force 3 digits in minor version number */
		if (MINOR_VERSION < 10) {
			version += "00"; //$NON-NLS-1$
		} else {
			if (MINOR_VERSION < 100) version += "0"; //$NON-NLS-1$
		}
		version += MINOR_VERSION;
		/* "r" followed by respective revision version starting with zero(0) */
		version += "r" + REVISION; //$NON-NLS-1$
	}
	return version;
}

}
