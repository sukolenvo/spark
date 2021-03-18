package spark.servlet;

import static spark.Spark.get;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;

import javax.servlet.FilterConfig;
import javax.servlet.ServletException;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spark.ExceptionHandlerImpl;
import spark.ExceptionMapper;
import spark.Request;
import spark.Response;
import spark.Spark;
import spark.util.SparkTestUtil;

public class ServletCleanupAfterRestart {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServletCleanupAfterRestart.class);

    @Test
    public void testErrorMapperAfterRestart() throws Exception {
        Server fooServer = startServer("foo");
        SparkTestUtil fooServerUtils = new SparkTestUtil(fooServer.getURI().getPort());
        Assert.assertEquals("expecting to return error from error handler",
                            "foo", fooServerUtils.get("/context/endpoint").body);
        fooServer.stop();

        Thread.sleep(500);

        Server barServer = startServer("bar");
        SparkTestUtil barServerUtils = new SparkTestUtil(barServer.getURI().getPort());
        Assert.assertEquals("foo server is stopped. Expecting 'bar' from error handler",
                            "bar", barServerUtils.get("/context/endpoint").body);
        barServer.stop();
    }

    private Server startServer(String errorMessage) throws Exception {
        final Server server = new Server();
        ServerConnector connector = new ServerConnector(server);

        // Set some timeout options to make debugging easier.
        connector.setIdleTimeout(1000 * 60 * 60);
        connector.setSoLingerTime(-1);
        connector.setPort(0);
        server.setConnectors(new Connector[] {connector});

        WebAppContext bb = new WebAppContext();
        bb.setServer(server);
        bb.setContextPath("/context");
        bb.setResourceBase(Files.createTempDirectory("temp").toString());
        bb.addFilter(new FilterHolder(new SparkFilter() {
            @Override
            protected SparkApplication[] getApplications(FilterConfig filterConfig) throws ServletException {
                return new SparkApplication[] {new ApplicationWithServletErrorHandling(errorMessage)};
            }

            @Override
            public void destroy() {
                super.destroy();
                Spark.stop();
            }
        }), "/*", null);

        server.setHandler(bb);
        CountDownLatch latch = new CountDownLatch(1);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LOGGER.info(">>> STARTING EMBEDDED JETTY SERVER for jUnit testing of SparkFilter");
                    server.start();
                    latch.countDown();
                    System.in.read();
                    LOGGER.info(">>> STOPPING EMBEDDED JETTY SERVER");
                    server.stop();
                    server.join();
                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(100);
                }
            }
        }).start();

        latch.await();
        return server;
    }

    private static class ApplicationWithServletErrorHandling implements SparkApplication {

        private final String errorMessage;

        private ApplicationWithServletErrorHandling(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        @Override
        public void init() {
            get("/endpoint", (req, res) -> {
                throw new IllegalStateException("Handling Failed!");
            });
            ExceptionMapper.getServletInstance().map(Exception.class,
                                                     new ExceptionHandlerImpl<Exception>(Exception.class) {
                                                         @Override
                                                         public void handle(Exception exception,
                                                                            Request request,
                                                                            Response response) {
                                                             response.body(errorMessage);
                                                         }
                                                     });
        }
    }
}
