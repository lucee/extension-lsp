package org.lucee.extension.lsp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lucee.extension.lsp.util.LSPUtil;

import lucee.commons.io.log.Log;
import lucee.loader.engine.CFMLEngine;
import lucee.loader.engine.CFMLEngineFactory;
import lucee.loader.util.Util;
import lucee.runtime.Component;
import lucee.runtime.PageContext;
import lucee.runtime.config.Config;
import lucee.runtime.config.ConfigWeb;

public class LSPEndpointFactory {
	private static final int DEFAULT_LSP_PORT = 2089; // Common LSP port
	private static final String DEFAULT_COMPONENT = "org.lucee.extension.lsp.LSPEndpoint";
	private ServerSocket serverSocket;
	private ExecutorService executor;
	private volatile boolean running = true;
	private CFMLEngine engine;
	private int port;
	private String cfcPath;
	private Log log;

	// TODO shutdown when the Lucee engine does shut down
	public LSPEndpointFactory(Config config) throws IOException {
		// setup config and utils
		engine = CFMLEngineFactory.getInstance();
		log = LSPUtil.getLog(config);
		port = engine.getCastUtil().toIntValue(Util._getSystemPropOrEnvVar("lucee.lsp.port", null), DEFAULT_LSP_PORT);
		cfcPath = engine.getCastUtil().toString(Util._getSystemPropOrEnvVar("lucee.lsp.component", null), DEFAULT_COMPONENT);
		if (Util.isEmpty(cfcPath, true)) cfcPath = DEFAULT_COMPONENT;

		log.debug("lsp", "LSP server port: " + port);
		log.debug("lsp", "LSP server component endpoint: " + cfcPath);

		startServer();
	}

	private void startServer() throws IOException {
		log.debug("lsp", "starting LSP server");
		try {
			serverSocket = new ServerSocket(port);
		}
		catch (IOException e) {
			error("lsp", e);
			throw e;
		}
		executor = Executors.newCachedThreadPool();

		// Start listening thread
		Thread listenerThread = new Thread(() -> {
			while (running) {
				try {
					Socket clientSocket = serverSocket.accept();
					executor.submit(() -> handleClient(clientSocket));
				}
				catch (IOException e) {
					if (running) {
						error("lsp", e);
					}
				}
			}
		}, "LSP-Listener");

		listenerThread.setDaemon(true);
		listenerThread.start();

		log.debug("lsp", "LSP server started");
	}

	private void handleClient(Socket clientSocket) {

		log.debug("lsp", "LSP server handle client");
		try {
			// Get input/output streams
			BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
			OutputStream out = clientSocket.getOutputStream();
			StringBuilder buffer = new StringBuilder();

			while (!clientSocket.isClosed()) {
				// Read incoming data into buffer
				char[] cbuf = new char[1024];
				int len;
				while ((len = reader.read(cbuf)) != -1) {
					buffer.append(cbuf, 0, len);

					// Process complete messages in the buffer
					while (true) {
						// Look for Content-Length header
						String content = buffer.toString();
						int headerIndex = content.indexOf("Content-Length: ");
						if (headerIndex == -1) break;

						// Parse content length
						int lengthStart = headerIndex + 16;
						int lengthEnd = content.indexOf("\r\n", lengthStart);
						if (lengthEnd == -1) break;

						int contentLength = Integer.parseInt(content.substring(lengthStart, lengthEnd));

						// Find start of JSON content
						int contentStart = content.indexOf("\r\n\r\n", lengthEnd);
						if (contentStart == -1) break;
						contentStart += 4;

						// Check if we have the complete message
						if (buffer.length() < contentStart + contentLength) break;

						// Extract the JSON message
						String jsonMessage = content.substring(contentStart, contentStart + contentLength);

						// Here you would call your component to handle the message
						String response = processMessage(jsonMessage);

						// Send response using LSP format
						if (response != null) {
							String header = "Content-Length: " + response.length() + "\r\n\r\n";
							out.write(header.getBytes());
							out.write(response.getBytes());
							out.flush();
						}

						// Remove processed message from buffer
						buffer.delete(0, contentStart + contentLength);
					}
				}
			}
		}
		catch (Exception e) {
			error("lsp", e);
		}
		finally {
			engine.getIOUtil().closeSilent(clientSocket);
		}
	}

	private String processMessage(String jsonMessage) {
		try {
			log.info("lsp", "Received message: " + jsonMessage);
			PageContext pc = LSPUtil.createPageContext((ConfigWeb) engine.getThreadConfig());
			Component cfc = engine.getCreationUtil().createComponentFromName(pc, cfcPath);

			String response = engine.getCastUtil().toString(cfc.call(pc, "execute", new Object[] { jsonMessage }));
			log.info("lsp", "response from component [" + cfcPath + "]: " + response);

			return response;
		}
		catch (Exception e) {
			error("lsp", e);
			return null;
		}
	}

	public void shutdown() throws IOException {
		running = false;
		if (executor != null) {
			executor.shutdown();
		}
		if (serverSocket != null && !serverSocket.isClosed()) {
			serverSocket.close();

		}
	}

	private void error(String type, Exception e) {
		// TODO remove the print out
		System.err.println(type);
		e.printStackTrace();
		log.error(type, e);
	}
}