package com.readict.lexical.audio;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.*;

/** Bounded independent file work; all submitted work ends before staging can be removed. */
final class AudioIo implements AutoCloseable {
    private final ExecutorService workers=Executors.newFixedThreadPool(4);
    private final ArrayDeque<Future<?>> pending=new ArrayDeque<>();
    interface Work {void run() throws IOException;}
    void submit(Work work) throws IOException {
        if(pending.size()==16)await(pending.removeFirst());
        pending.addLast(workers.submit(()->{work.run();return null;}));
    }
    private static void await(Future<?> task) throws IOException {
        boolean interrupted=false;
        try{
            for(;;)try{task.get();return;}catch(InterruptedException e){interrupted=true;}
            catch(ExecutionException e){
                if(e.getCause() instanceof IOException io)throw io;
                if(e.getCause() instanceof RuntimeException runtime)throw runtime;
                if(e.getCause() instanceof Error error)throw error;
                throw new IOException("AUDIO_FILE_WORK_FAILED",e.getCause());
            }
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    @Override public void close() throws IOException {
        Throwable failure=null;
        try{
            while(!pending.isEmpty())try{await(pending.removeFirst());}
            catch(Throwable e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        }finally{workers.shutdown();}
        if(failure instanceof IOException io)throw io;
        if(failure instanceof RuntimeException runtime)throw runtime;
        if(failure instanceof Error error)throw error;
    }
}
