GraalVM gives you a dedicated **Agent application** that comes with rich
monitoring features to allow developers, integrators, and IT staff to monitor
the performance of user programs and the health status of the virtual machine.

The GraalVM monitoring component is called the Agent and can be easily enabled
by providing command line option `--agent` to the executables.

__Disclaimer__: The Agent is currently an __experimental__ feature
provided by the __GraalVM Enterprise Edition__ available for download
from the [Oracle Technology Network](http://www.oracle.com/technetwork/oracle-labs/program-languages/downloads/index.html).

### GraalVM Instruments

The Agent uses instruments that implement the
[Truffle Instrumentation API](http://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/instrumentation/package-summary.html).

In future, additional instrumentation tools can be added either by
the GraalVM development team or third party developers.

### Running an Example for Agent Monitoring
Get familiar with using the GraalVM Agent with this Hello World example.

1. Save the following code snippet as `HellWorld.js`:

    ```
    var http = require('http');

    var server = http.createServer(function (request, response) {
      let host = request.headers.host;
      let index = host.indexOf(':');
      if (index > 0) {
        host = host.substring(0, index);
      }
      response.writeHead(200, {"Content-Type": "text/plain"});
      response.end("Hello "+host+"!\n");
    });

    server.listen(8000);

    console.log("Server running at http://localhost:8000/");
    ```

2. Launch the Graal Enterprise Monitoring Agent Server `gemasrv` using this command:

    ```
    gemasrv
    Agent http server is running in unsecure mode!
    Use Java VM properties:
        -Dkey.store.file.path to specify the path to the .jsk file.
        -Dkey.store.file.password to specify the password used to unlock the keystore
        -Dkey.store.key.recover.password to specify the password for recovering keys. If is not specified the key.store.file.password is used.
    Agent http server started on port 8080, reachable at:
    http://localhost:8080/info
    ```

    The program starts without SSL, with a warning about unsecure mode,
    and the Monitoring Agent web application is available at `http://localhost:8080/`.

    When you open this page, you can see there are no virtual machines attached yet.

    Note: To run the server on a different port, use the `--port <port_number>` option.


3. Launch GraalVM `node` to monitor `HellWorld.js` using this command:

    ```
    node --agent HelloWorld.js
    Server running at http://localhost:8000/
    ```

    The GraalVM starts and attaches itself to the Monitoring Agent Server running by default on the localhost:8080 port.
    To attach the GraalVM to the server running on a different host:port, use the `--agent=<[host:]port>` option.

    Now, when you open the Monitoring Agent's page, you can see the virtual machine is running with some
    system information displayed by default.

    For script developers who are interested in more information about
    `HelloWorld.js`, the following script is available in the [Polyglot Engine](http://www.graalvm.org/truffle/javadoc/index.html?com/oracle/truffle/api/vm/PolyglotEngine.html) section.

4. In the Agent window, select `Polyglot Engine - 1` from the second drop-down
box at the top. You should see the sources loaded in the Polyglot Engine
and the available instruments as shown in the following image:

![](/docs/img/AgentOpened.png)

The Agent window shows loaded sources on the left where the `HelloWorld.js` source
can be found under the **FileSystem** node . The area in the middle provides
detailed information about sources or gathered data from instruments. The
right-most column allows you to enable various loaded instruments and
set their properties.

Let's take a look at CPU sampling with our script to see what takes
the most time.

1. Enable **Agent CPU Sampler**.
2. In the settings section, select **Start Sampling**.
3. Reload the application at `http://localhost:8000/` so that the Agent can
gather the data.
4. Return to the Agent window.
5. In the **CPU Sampler** section, select **Stop Sampling**.

The Agent page refreshes and results should display as follows:

![ ](/docs/img/CPUSampler.png  "Agent and CPU Sampler data")

It is also possible to see detailed data in the source code. To do this, open
the `HelloWorld.js` file in the Agent tab and the select **Agent CPU Sampler**
from the drop-down box. Sources should display with data available as follows:

![](/docs/img/CPUSamplerDetail.png)

### JVM Mode Only Instruments

Some monitoring instruments, such as the Specialization Instrument, are
available in `--jvm` mode only for the GraalVM launchers.

In this case, you should launch `HelloWorld.js` as follows:

```
node --jvm --agent HelloWorld.js
```

Then do the following:
1. Open Agent and enable **Agent Specializations** from the list of instruments.
2. Reload the `HelloWorld.js` application in your browser.
3. Return to the Agent window and open the `HelloWorld.js` script.
4. Select **Agent Specializations** from the drop-down box next to the
**filename** tab.

Data such as the following displays specializations of various JavaScript
statements:

![ ](/docs/img/SpecializationInstrument.png  "Specialization Instrument")
