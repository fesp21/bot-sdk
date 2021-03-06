package com.wire.bots.sdk.server.tasks;

import com.google.common.collect.ImmutableMultimap;
import com.wire.bots.sdk.ClientRepo;
import com.wire.bots.sdk.Configuration;
import com.wire.bots.sdk.Logger;
import com.wire.bots.sdk.WireClient;
import com.wire.bots.sdk.server.model.Member;

import java.io.File;
import java.io.FileFilter;
import java.io.PrintWriter;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task class that will post text into ALL conversation that this Service has!!!
 * Usage:
 * curl -X POST http://localhost:8051/tasks/massive_broadcast --data "text=Hello"
 * You need to exec this task against your `admin port`
 * For more on dropwizard tasks check out: http://www.dropwizard.io/1.0.5/docs/manual/core.html#tasks
 */
public class BroadcastAllTask extends TaskBase {
    protected final Configuration conf;
    protected final ClientRepo repo;
    AtomicInteger succeeded = new AtomicInteger(0);
    AtomicInteger failed = new AtomicInteger(0);

    public BroadcastAllTask(Configuration conf, ClientRepo repo) {
        super("broadcast");
        this.conf = conf;
        this.repo = repo;
    }

    @Override
    public void execute(final ImmutableMultimap<String, String> parameters, final PrintWriter output) throws Exception {
        int threads = extract(parameters, "th", 20);
        ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(threads);

        final String text = extractString(parameters, "text");
        if (text.isEmpty()) {
            output.println("Are you missing `text` param?");
            return;
        }

        Date start = new Date();
        for (File botDir : getCryptoDirs()) {
            final String botId = botDir.getName();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    send(output, text, botId);
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.HOURS);

        Date end = new Date();
        long l = end.getTime() - start.getTime();
        output.printf("Processed: %,d/%,d convs in %,dsec\n",
                succeeded.get(),
                failed.get(),
                TimeUnit.MILLISECONDS.toSeconds(l));

        succeeded.set(0);
        failed.set(0);
    }

    private void send(PrintWriter output, String text, String botId) {
        try {
            WireClient client = repo.getWireClient(botId);
            if (client != null) {
                List<Member> members = client.getConversation().members;
                if (!members.isEmpty()) {
                    client.sendText(text);
                    int curr = succeeded.incrementAndGet();
                    if (curr % 100 == 0) {
                        output.println(curr);
                        output.flush();
                    }
                }
            }
        } catch (Exception e) {
            Logger.error("Bot: %s. Error: %s", botId, e.getMessage());
            output.println("Failed for botId: " + botId);
            output.flush();
            failed.incrementAndGet();
        }
    }

    private File[] getCryptoDirs() {
        File dir = new File(conf.getCryptoDir());
        return dir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                String botId = file.getName();
                return repo.getWireClient(botId) != null;
            }
        });
    }
}
