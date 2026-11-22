package com.readict.lexical.audio;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AudioIoTest {
    @Test void independentFilesUseFourWorkersAndCloseWaitsForEveryTask() throws Exception {
        var started=new CountDownLatch(4);var release=new CountDownLatch(1);var finished=new AtomicInteger();
        try(var io=new AudioIo()){
            for(int i=0;i<4;i++)io.submit(()->{started.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("TEST_RELEASE_TIMEOUT");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}finished.incrementAndGet();});
            try{assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThat(finished).hasValue(0);}finally{release.countDown();}
        }
        assertThat(finished).hasValue(4);
    }
    @Test void failedFileStillDrainsOtherWorkBeforeStagingCanBeDeleted() {
        var finished=new CountDownLatch(1);
        assertThatThrownBy(()->{
            try(var io=new AudioIo()){
                io.submit(()->{throw new IOException("SELF_AUTHORED_FILE_FAILURE");});
                io.submit(()->{try{Thread.sleep(40);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}finished.countDown();});
            }
        }).isInstanceOf(IOException.class).hasMessage("SELF_AUTHORED_FILE_FAILURE");
        assertThat(finished.getCount()).isZero();
    }
}
