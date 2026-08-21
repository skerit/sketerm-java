package be.elevenways.sketerm.rpc;

import be.elevenways.sketerm.process.SketermProcess;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

/**
 * Newline-delimited JSON over a child process' stdin and stdout.
 */
public final class StdioTransport implements SketermTransport {

    private final SketermProcess process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final Object writeLock = new Object();

    public StdioTransport(SketermProcess process) {
        this.process = process;
        this.writer = process.getStdin();
        this.reader = process.getStdout();
    }

    public SketermProcess getProcess() {
        return this.process;
    }

    @Override
    public void send(String message) {

        if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            throw new TransportException("Refusing to send a message containing a raw newline");
        }

        synchronized (this.writeLock) {
            try {
                this.writer.write(message);
                this.writer.write('\n');
                this.writer.flush();
            } catch (IOException e) {
                throw new TransportException("Failed to write to the child: "
                        + this.describeFailureContext(), e);
            }
        }
    }

    @Override
    public String receive() {

        try {
            return this.reader.readLine();
        } catch (IOException e) {
            // A closed pipe during shutdown is end of stream, not a failure.
            if (!this.process.isAlive()) {
                return null;
            }

            throw new TransportException("Failed to read from the child: "
                    + this.describeFailureContext(), e);
        }
    }

    @Override
    public String describeFailureContext() {
        return this.process.describeFailureContext();
    }

    @Override
    public void close() {
        this.process.close();
    }
}
