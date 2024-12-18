package org.lucee.extension.lsp.util;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

import javax.servlet.http.Cookie;

import lucee.commons.io.log.Log;
import lucee.loader.engine.CFMLEngine;
import lucee.loader.engine.CFMLEngineFactory;
import lucee.loader.util.Util;
import lucee.runtime.PageContext;
import lucee.runtime.config.Config;
import lucee.runtime.config.ConfigServer;
import lucee.runtime.config.ConfigWeb;
import lucee.runtime.exp.PageException;
import lucee.runtime.type.Struct;

public class LSPUtil {
	private static final long TIMEOUT = 3000;

	public static String getLogName(ConfigWeb config) {
		try {
			config.getLog("lsp");
			return "lsp";
		}
		catch (Exception e) {
			return "application";
		}
	}

	public static boolean hasLogLevel(Config config, int level) {
		Log log = getLog(config);
		return log != null && log.getLogLevel() <= level;
	}

	public static Log getLog(Config config) {
		if (config == null) config = CFMLEngineFactory.getInstance().getThreadConfig();
		if (config instanceof ConfigServer) {
			// we only log to config Server if there is no web context
			Config cw = CFMLEngineFactory.getInstance().getThreadConfig();
			if (cw != null) config = cw;
		}
		if (config == null) return null;
		try {
			Log log = config.getLog("lsp");
			if (log == null) log = config.getLog("application");
			if (log != null) return log;
		}
		catch (Exception e) {
			Log log = config.getLog("application");
			log.error("lsp", e);
			return log;
		}
		return null;
	}

	public static String getSystemPropOrEnvVar(String name, String defaultValue) { // FUTURE remove _ or move to CFMLEngineFactory.getSystemPropOrEnvVar()

		if (Util.isEmpty(name)) return defaultValue;

		// env
		String value = System.getenv(name);
		if (!Util.isEmpty(value)) return value;

		// prop
		value = System.getProperty(name, null);
		if (!Util.isEmpty(value)) return value;

		// try to convert prop to env
		String key = name.replace('.', '_').toUpperCase();
		value = System.getenv(key);
		if (!Util.isEmpty(value)) return value;

		// try to convert env to prop
		key = name.replace('_', '.').toLowerCase();
		value = System.getProperty(key, null);
		if (!Util.isEmpty(value)) return value;

		return defaultValue;
	}

	public static PageContext createPageContext(final ConfigWeb cw) throws PageException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		return createPageContext(cw, baos, "/", "", TIMEOUT);
	}

	private static PageContext createPageContext(final ConfigWeb cw, final OutputStream os, final String path, String qs, long timeout) throws PageException {
		try {
			CFMLEngine eng = CFMLEngineFactory.getInstance();
			Class<?> clazz = eng.getClassUtil().loadClass("lucee.runtime.thread.ThreadUtil");
			Class<?> clazzPairArray = eng.getClassUtil().loadClass("lucee.commons.lang.Pair[]");

			Method method = clazz.getMethod("createPageContext", new Class[] { ConfigWeb.class, OutputStream.class, String.class, String.class, String.class, Cookie[].class,
					clazzPairArray, byte[].class, clazzPairArray, Struct.class, boolean.class, long.class });

			return (PageContext) method.invoke(null, new Object[] { cw, os, "", path, qs, new Cookie[0], null, null, null, null, true, timeout });
		}
		catch (Exception e) {
			throw CFMLEngineFactory.getInstance().getCastUtil().toPageException(e);
		}
	}

	public static void releasePageContext(PageContext pc) {
		CFMLEngineFactory.getInstance().releasePageContext(pc, true);
	}

}
