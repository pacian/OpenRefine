/*

Copyright 2010, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,           
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY           
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package org.openrefine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.util.ShutdownHookManager;
import org.openrefine.ProjectManager;
import org.openrefine.RefineModel;
import org.openrefine.commands.Command;
import org.openrefine.importing.ImportingManager;
import org.openrefine.io.FileProjectManager;
import org.openrefine.io.OrderedLocalFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.mit.simile.butterfly.Butterfly;
import edu.mit.simile.butterfly.ButterflyModule;
import scala.Function0;
import scala.runtime.BoxedUnit;

public class RefineServlet extends Butterfly {
    
    static public String VERSION = "";
    static public String REVISION = "";
    static public String FULL_VERSION = "";
    static public String FULLNAME = "OpenRefine ";


    static public final String AGENT_ID = "/en/google_refine"; // TODO: Unused?  Freebase ID
    
    static final long serialVersionUID = 2386057901503517403L;

    static private final String JAVAX_SERVLET_CONTEXT_TEMPDIR = "javax.servlet.context.tempdir";
    private File tempDir = null;

    static private RefineServlet s_singleton;
    static private File s_dataDir;
    static private JavaSparkContext s_context;
    
    static final private Map<String, Command> commands = new HashMap<String, Command>();

    // timer for periodically saving projects
    static private ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();

    static final Logger logger = LoggerFactory.getLogger("refine");

    static protected class AutoSaveTimerTask implements Runnable {
        @Override
        public void run() {
            try {
                ProjectManager.singleton.save(false); // quick, potentially incomplete save
            } catch (final Throwable e) {
                // Not the best, but we REALLY want this to keep trying
            }
        }
    }

    @Override
    public void init() throws ServletException {
        super.init();
        
        Thread.currentThread().setContextClassLoader(JavaSparkContext.class.getClassLoader());
        int defaultParallelism = 4;
        try {
        	String parallelism = getInitParameter("refine.defaultParallelism");
        	defaultParallelism = Integer.parseInt(parallelism == null ? "" : parallelism);
        } catch(NumberFormatException e) {
        	;
        }
        
        // set up Hadoop on Windows
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
        	try {
				System.setProperty("hadoop.home.dir", new File("server/target/lib/native/windows/hadoop").getCanonicalPath());
			} catch (IOException e) {
				logger.warn("unable to locate Windows Hadoop binaries, this will leave temporary files behind");
			}
        }
        
        s_context = new JavaSparkContext(
        		new SparkConf()
        		.setAppName("OpenRefine")
        		.setMaster(String.format("local[%d]", defaultParallelism)));
        s_context.setLogLevel("WARN");
        s_context.hadoopConfiguration().set("fs.file.impl", OrderedLocalFileSystem.class.getName());
        VERSION = getInitParameter("refine.version");
        REVISION = getInitParameter("refine.revision");    
        
        if (VERSION.equals("$VERSION")) {
            VERSION = RefineModel.VERSION;
        }
        if (REVISION.equals("$REVISION")) {
            ClassLoader classLoader = getClass().getClassLoader();
            try {
                InputStream gitStats = classLoader.getResourceAsStream("git.properties");
                ObjectMapper mapper = new ObjectMapper();
                ObjectNode parsedGit = mapper.readValue(gitStats, ObjectNode.class);
                REVISION = parsedGit.get("git.commit.id.abbrev").asText("TRUNK");
            } catch (IOException e) {
                REVISION = "TRUNK";
            }
        }
        
        FULL_VERSION = VERSION + " [" + REVISION + "]";
        FULLNAME += FULL_VERSION;

        logger.info("Starting " + FULLNAME + "...");
        
        s_singleton = this;

        logger.trace("> initialize");

        String data = getInitParameter("refine.data");

        if (data == null) {
            throw new ServletException("can't find servlet init config 'refine.data', I have to give up initializing");
        }
        logger.error("initializing FileProjectManager with dir");
        logger.error(data);
        s_dataDir = new File(data);
        FileProjectManager.initialize(s_context, s_dataDir);
        ImportingManager.initialize(this);
        
        // Set up hook to save projects when spark shuts down
        int priority = ShutdownHookManager.SPARK_CONTEXT_SHUTDOWN_PRIORITY() + 10;
		ShutdownHookManager.addShutdownHook(priority, sparkShutdownHook());

	    long AUTOSAVE_PERIOD = Long.parseLong(getInitParameter("refine.autosave"));

        service.scheduleWithFixedDelay(new AutoSaveTimerTask(), AUTOSAVE_PERIOD, 
                AUTOSAVE_PERIOD, TimeUnit.MINUTES);

        logger.trace("< initialize");
    }

    @Override
    public void destroy() {
        logger.trace("> destroy");

        // cancel automatic periodic saving and force a complete save.
        if (_timer != null) {
            _timer.cancel();
            _timer = null;
        }
        if (ProjectManager.singleton != null) {
            ProjectManager.singleton.dispose();
            ProjectManager.singleton = null;
        }

        logger.trace("< destroy");

        super.destroy();
    }

    @Override
    public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (request.getPathInfo().startsWith("/command/")) {
            String commandKey = getCommandKey(request);
            Command command = commands.get(commandKey);
            if (command != null) {
                if (request.getMethod().equals("GET")) {
                    if (!logger.isTraceEnabled() && command.logRequests()) {
                        logger.info("GET {}", request.getPathInfo());
                    }
                    logger.trace("> GET {}", commandKey);
                    command.doGet(request, response);
                    logger.trace("< GET {}", commandKey);
                } else if (request.getMethod().equals("POST")) {
                    if (!logger.isTraceEnabled() && command.logRequests()) {
                        logger.info("POST {}", request.getPathInfo());
                    }
                    logger.trace("> POST {}", commandKey);
                    command.doPost(request, response);
                    logger.trace("< POST {}", commandKey);
                } else if (request.getMethod().equals("PUT")) {
                    if (!logger.isTraceEnabled() && command.logRequests()) {
                        logger.info("PUT {}", request.getPathInfo());
                    }
                    logger.trace("> PUT {}", commandKey);
                    command.doPut(request, response);
                    logger.trace("< PUT {}", commandKey);
                } else if (request.getMethod().equals("DELETE")) {
                    if (!logger.isTraceEnabled() && command.logRequests()) {
                        logger.info("DELETE {}", request.getPathInfo());
                    }
                    logger.trace("> DELETE {}", commandKey);
                    command.doDelete(request, response);
                    logger.trace("< DELETE {}", commandKey);
                } else {
                    response.sendError(405);
                }
            } else {
                response.sendError(404);
            }
        } else {
            super.service(request, response);
        }
    }
    
    public ButterflyModule getModule(String name) {
        return _modulesByName.get(name);
    }

    protected String getCommandKey(HttpServletRequest request) {
        // A command path has this format: /command/module-name/command-name/...
        
        String path = request.getPathInfo().substring("/command/".length());
        
        int slash1 = path.indexOf('/');
        if (slash1 >= 0) {
            int slash2 = path.indexOf('/', slash1 + 1);
            if (slash2 > 0) {
                path = path.substring(0, slash2);
            }
        }
        
        return path;
    }

    public File getTempDir() {
        if (tempDir == null) {
            tempDir = (File) _config.getServletContext().getAttribute(JAVAX_SERVLET_CONTEXT_TEMPDIR);
            if (tempDir == null) {
                throw new RuntimeException("This app server doesn't support temp directories");
            }
        }
        return tempDir;
    }

    public File getTempFile(String name) {
        return new File(getTempDir(), name);
    }
    
    public File getCacheDir(String name) {
        File dir = new File(new File(s_dataDir, "cache"), name);
        dir.mkdirs();
        
        return dir;
    }

    public String getConfiguration(String name, String def) {
        return null;
    }
    
    /**
     * Register a single command.
     *
     * @param module the module the command belongs to
     * @param name command verb for command
     * @param commandObject object implementing the command
     * @return true if command was loaded and registered successfully
     */
    protected boolean registerOneCommand(ButterflyModule module, String name, Command commandObject) {
        return registerOneCommand(module.getName() + "/" + name, commandObject);
    }
    
    /**
     * Register a single command.
     *
     * @param path path for command
     * @param commandObject object implementing the command
     * @return true if command was loaded and registered successfully
     */
    protected boolean registerOneCommand(String path, Command commandObject) {
        if (commands.containsKey(path)) {
            return false;
        }
        
        commandObject.init(this);
        commands.put(path, commandObject);
        
        return true;
    }

    // Currently only for test purposes
    protected boolean unregisterCommand(String verb) {
        return commands.remove(verb) != null;
    }
    
    /**
     * Register a single command. Used by extensions.
     *
     * @param module the module the command belongs to
     * @param name command verb for command
     * @param commandObject object implementing the command
     *            
     * @return true if command was loaded and registered successfully
     */
    static public boolean registerCommand(ButterflyModule module, String commandName, Command commandObject) {
        return s_singleton.registerOneCommand(module, commandName, commandObject);
    }
   

    static public void cacheClass(Class<?> klass) {
        RefineModel.cacheClass(klass);
    }
    
    static public Class<?> getClass(String className) throws ClassNotFoundException {
        return RefineModel.getClass(className);
    }
    
    static public void registerClassMapping(String from, String to) {
        RefineModel.registerClassMapping(from, to);
    }
    
    
    static public void setUserAgent(URLConnection urlConnection) {
        if (urlConnection instanceof HttpURLConnection) {
            setUserAgent((HttpURLConnection) urlConnection);
        }
    }
    
    static public void setUserAgent(HttpURLConnection httpConnection) {
        httpConnection.addRequestProperty("User-Agent", getUserAgent());
    }

    static public String getUserAgent() {
        return "OpenRefine/" + FULL_VERSION;
    }
    
    static public JavaSparkContext getSparkContext() {
    	return s_context;
    }
    
    static private Function0<BoxedUnit> sparkShutdownHook() {
    	return new Function0<BoxedUnit>() {

			@Override
			public BoxedUnit apply() {
				if (ProjectManager.singleton != null) {
		            ProjectManager.singleton.dispose();
		            ProjectManager.singleton = null;
		        }
				return BoxedUnit.UNIT;
			}
    		
    	};
 
    }
}
