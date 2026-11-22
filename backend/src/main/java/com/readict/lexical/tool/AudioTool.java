package com.readict.lexical.tool;

import static com.readict.lexical.core.DictionaryJson.*;
import java.nio.file.*;
import java.util.*;
import com.readict.lexical.audio.*;
import com.readict.shared.config.DatabaseEnvironmentGuard;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import javax.sql.DataSource;

public final class AudioTool {
    private AudioTool() {}
    public static ConfigurableApplicationContext start(String profile){
        var app=new SpringApplication(AudioToolConfiguration.class);app.setWebApplicationType(WebApplicationType.NONE);app.setBannerMode(Banner.Mode.OFF);app.addInitializers(new DatabaseEnvironmentGuard());
        var context=app.run("--spring.profiles.active="+profile,"--spring.main.web-application-type=none","--mybatis-plus.mapper-locations=classpath*:/mapper/lexical/*.xml");
        try(var connection=context.getBean(DataSource.class).getConnection()){check(connection.getCatalog().equals("readict_"+profile)&&connection.getMetaData().getUserName().startsWith("readict_"+profile+"_app@"),"AUDIO_DATABASE_IDENTITY_INVALID");return context;}catch(Exception e){context.close();throw new DictionaryFailure("AUDIO_DATABASE_IDENTITY_INVALID");}
    }
    public static int run(String[] args){
        try{
            String command=args[0];check(Set.of("audio-validate","audio-import","audio-inspect","audio-activate").contains(command),"AUDIO_USAGE");var options=new HashMap<String,String>();
            for(int i=1;i<args.length;i++){
                String key=args[i];if(key.startsWith("--spring.profiles.active=")){check(options.put("profile",key.substring(key.indexOf('=')+1))==null,"AUDIO_USAGE");continue;}
                check(Set.of("--bundle","--dataset","--expected-active-version","--resume","--allow-local-subset").contains(key)&&!options.containsKey(key),"AUDIO_USAGE");
                if(Set.of("--resume","--allow-local-subset").contains(key))options.put(key,"true");else{check(++i<args.length&&!args[i].startsWith("--"),"AUDIO_USAGE");options.put(key,args[i]);}
            }
            Set<String> allowed=switch(command){case "audio-validate"->Set.of("--bundle","--allow-local-subset");case "audio-import"->Set.of("--bundle","--resume","profile","--allow-local-subset");case "audio-inspect"->Set.of("--dataset","profile");default->Set.of("--dataset","--expected-active-version","profile","--allow-local-subset");};check(allowed.containsAll(options.keySet()),"AUDIO_USAGE");boolean allow=options.containsKey("--allow-local-subset");Object result;
            if(command.equals("audio-validate")){check(options.containsKey("--bundle"),"AUDIO_USAGE");var bundle=new AudioBundleValidator().validate(Path.of(options.get("--bundle")),allow);result=Map.of("datasetId",bundle.manifest().path("datasetId").asText(),"status","AUDIO_LOCAL_TECHNICAL_VALIDATED","releaseEligible",false);}
            else{
                check(Set.of("test","dev").contains(options.getOrDefault("profile","")),"AUDIO_PROFILE_REQUIRED");
                if(command.equals("audio-import")){check(options.containsKey("--bundle"),"AUDIO_USAGE");try(var frozen=new AudioBundleValidator().freezeForImport(Path.of(options.get("--bundle")),allow);var context=start(options.get("profile"))){result=context.getBean(AudioImporter.class).importBundle(frozen.bundle(),options.containsKey("--resume"));}}
                else{check(options.containsKey("--dataset"),"AUDIO_USAGE");UUID.fromString(options.get("--dataset"));long version=command.equals("audio-activate")?Long.parseLong(options.getOrDefault("--expected-active-version","-1")):0;check(version>=0,"AUDIO_USAGE");try(var context=start(options.get("profile"))){var importer=context.getBean(AudioImporter.class);result=command.equals("audio-inspect")?importer.inspect(options.get("--dataset")):importer.activate(options.get("--dataset"),version,allow);}}
            }
            System.out.println(json(result));return 0;
        }catch(DictionaryFailure e){System.err.println(json(Map.of("code",e.code)));return e.code.contains("QUALITY")||e.code.equals("AUDIO_POLICY_NOT_ALLOWED")?3:e.code.contains("LEASE")||e.code.contains("DATABASE")||e.code.contains("CONFLICT")||e.code.contains("RESUME")?4:2;}
        catch(org.springframework.dao.DataAccessException e){System.err.println("{\"code\":\"AUDIO_DATABASE_FAILED\"}");return 4;}
        catch(java.io.IOException|IllegalArgumentException e){System.err.println("{\"code\":\"AUDIO_INPUT_INVALID\"}");return 2;}
        catch(Exception e){System.err.println("{\"code\":\"AUDIO_TOOL_FAILED\"}");return 5;}
    }
}
