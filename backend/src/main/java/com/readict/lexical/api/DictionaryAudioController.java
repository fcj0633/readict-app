package com.readict.lexical.api;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import jakarta.servlet.http.*;
import com.readict.lexical.audio.*;
import com.readict.lexical.core.DictionaryJson.DictionaryFailure;
import com.readict.shared.web.Problems;
import com.readict.shared.web.RequestIdFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class DictionaryAudioController {
    private static final Logger LOG=LoggerFactory.getLogger(DictionaryAudioController.class);
    private final AudioPlaybackService playback;
    private final Problems problems;
    public DictionaryAudioController(AudioPlaybackService playback,Problems problems){this.playback=playback;this.problems=problems;}

    @RequestMapping(value="/api/v1/dictionary/audio/{audioId}",method={RequestMethod.GET,RequestMethod.HEAD})
    public void audio(@PathVariable String audioId,HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!audioId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")){problems.write(request,response,400,"INVALID_AUDIO_ID","音频标识不合法");return;}
        try{
            var resource=playback.resolve(UUID.fromString(audioId));
            long length=resource.bytes();Path path=resource.path();String sha=resource.sha256();
            String etag='"'+sha+'"';response.setHeader("Accept-Ranges","bytes");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Cache-Control","private, no-cache");response.setHeader("ETag",etag);response.setContentType("audio/mpeg");response.setHeader("Content-Disposition","inline; filename=\"pronunciation.mp3\"");
            String condition=request.getHeader("If-None-Match");
            if(condition!=null&&Arrays.stream(condition.split(",")).map(String::trim).anyMatch(x->x.equals("*")||x.equals(etag)||x.equals("W/"+etag))){response.setStatus(304);return;}
            if(request.getMethod().equals("HEAD")){response.setStatus(200);response.setContentLengthLong(length);return;}
            long start=0,end=length-1;String range=request.getHeader("Range"),ifRange=request.getHeader("If-Range");
            if(range!=null&&(ifRange==null||ifRange.equals(etag))){
                long[] selected=parseRange(range,length);
                if(selected==null){response.setStatus(416);response.setHeader("Content-Range","bytes */"+length);response.setContentLengthLong(0);return;}
                start=selected[0];end=selected[1];response.setStatus(206);response.setHeader("Content-Range","bytes "+start+"-"+end+"/"+length);
            }else response.setStatus(200);
            response.setContentLengthLong(end-start+1);
            try(var in=new RandomAccessFile(path.toFile(),"r")){
                in.seek(start);long remaining=end-start+1;byte[] bytes=new byte[16384];var output=response.getOutputStream();
                while(remaining>0){int n=in.read(bytes,0,(int)Math.min(bytes.length,remaining));if(n<0)throw new EOFException();output.write(bytes,0,n);remaining-=n;}
            }
        }catch(DictionaryFailure e){if(!response.isCommitted())problems.write(request,response,e.code.equals("AUDIO_NOT_FOUND")?404:503,e.code.equals("AUDIO_NOT_FOUND")?"AUDIO_NOT_FOUND":"AUDIO_NOT_READY","音频暂不可用");}
        catch(NoSuchFileException e){if(!response.isCommitted())problems.write(request,response,404,"AUDIO_NOT_FOUND","音频不可用");LOG.warn("Audio unavailable: requestId={} audioId={} code=AUDIO_NOT_FOUND",RequestIdFilter.id(request),audioId);}
        catch(IOException|DataAccessException e){
            LOG.warn("Audio read interrupted: requestId={} audioId={} code=AUDIO_READ_FAILED",RequestIdFilter.id(request),audioId);
            if(!response.isCommitted()){response.reset();problems.write(request,response,503,"AUDIO_READ_FAILED","音频读取失败");}
            else try{response.getOutputStream().close();}catch(IOException ignored){/* Peer already closed. */}
        }
    }
    public static long[] parseRange(String range,long length){
        if(!range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")||length<=0)return null;
        try{String[] p=range.substring(6).split("-",-1);long start,end;
            if(p[0].isEmpty()){long suffix=Long.parseLong(p[1]);if(suffix<=0)return null;start=Math.max(0,length-suffix);end=length-1;}
            else{start=Long.parseLong(p[0]);end=p[1].isEmpty()?length-1:Math.min(length-1,Long.parseLong(p[1]));}
            return start>=length||end<start?null:new long[]{start,end};
        }catch(NumberFormatException e){return null;}
    }
}
