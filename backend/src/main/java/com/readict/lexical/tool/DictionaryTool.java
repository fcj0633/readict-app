package com.readict.lexical.tool;

import static com.readict.lexical.core.DictionaryJson.*;
import java.nio.file.Path;
import java.util.*;
import com.readict.lexical.core.*;
import com.readict.shared.config.DatabaseEnvironmentGuard;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import javax.sql.DataSource;

public final class DictionaryTool {
    private DictionaryTool() {}
    public static ConfigurableApplicationContext start(String profile) {
        var app=new SpringApplication(DictionaryToolConfiguration.class);app.setWebApplicationType(WebApplicationType.NONE);app.setBannerMode(Banner.Mode.OFF);app.addInitializers(new DatabaseEnvironmentGuard());
        ConfigurableApplicationContext context;try{context=app.run("--spring.profiles.active="+profile,"--spring.main.web-application-type=none","--mybatis-plus.mapper-locations=classpath*:/mapper/lexical/*.xml");}catch(RuntimeException e){throw new DictionaryFailure("DATABASE_START_FAILED");}
        try(var c=context.getBean(DataSource.class).getConnection()) {check(c.getCatalog().equals("readict_"+profile)&&c.getMetaData().getUserName().startsWith("readict_"+profile+"_app@"),"DATABASE_IDENTITY_INVALID");return context;}catch(Exception e){context.close();throw new DictionaryFailure("DATABASE_IDENTITY_INVALID");}
    }
    public static void main(String[] args) {int code=run(args);if(code!=0)System.exit(code);}
    static int run(String[] args) {
        if(args.length>0&&args[0].startsWith("audio-"))return AudioTool.run(args);
        try {
            check(args.length>0,"USAGE_INVALID");String command=args[0];check(Set.of("validate","import","inspect","activate").contains(command),"USAGE_INVALID");Map<String,String> options=new HashMap<>();
            for(int i=1;i<args.length;i++){String a=args[i];if(a.startsWith("--spring.profiles.active=")){check(options.put("profile",a.substring(a.indexOf('=')+1))==null,"USAGE_INVALID");continue;}check(Set.of("--bundle","--dataset","--expected-active-version","--allow-local-mvp","--resume").contains(a)&&!options.containsKey(a),"USAGE_INVALID");if(Set.of("--allow-local-mvp","--resume").contains(a))options.put(a,"true");else{check(i+1<args.length&&!args[i+1].startsWith("--"),"USAGE_INVALID");options.put(a,args[++i]);}}
            Set<String> allowed=switch(command){case "validate"->Set.of("--bundle","--allow-local-mvp");case "import"->Set.of("--bundle","--allow-local-mvp","--resume","profile");case "inspect"->Set.of("--dataset","profile");default->Set.of("--dataset","--expected-active-version","--allow-local-mvp","profile");};check(allowed.containsAll(options.keySet()),"USAGE_INVALID");boolean allow=options.containsKey("--allow-local-mvp");Object result;
            if(command.equals("validate")){check(options.containsKey("--bundle"),"USAGE_INVALID");var v=new BundleValidator().validate(Path.of(options.get("--bundle")),allow);result=Map.of("datasetId",v.manifest().path("datasetId").asText(),"status","LOCAL_MVP_VALIDATED","releaseEligible",false);}
            else{String profile=options.get("profile");check(Set.of("dev","test").contains(profile==null?"":profile),"PROFILE_REQUIRED");if(command.equals("import")){check(options.containsKey("--bundle"),"USAGE_INVALID");try(var frozen=new BundleValidator().freeze(Path.of(options.get("--bundle")),allow);var context=start(profile)){result=context.getBean(DictionaryImporter.class).importBundle(frozen.bundle(),options.containsKey("--resume"));}}else{check(options.containsKey("--dataset"),"USAGE_INVALID");UUID.fromString(options.get("--dataset"));if(command.equals("activate"))check(options.containsKey("--expected-active-version")&&Long.parseLong(options.get("--expected-active-version"))>=0,"USAGE_INVALID");try(var context=start(profile)){var importer=context.getBean(DictionaryImporter.class);result=command.equals("inspect")?importer.inspect(options.get("--dataset")):importer.activate(options.get("--dataset"),Long.parseLong(options.get("--expected-active-version")),allow);}}}
            System.out.println(json(result));return 0;
        }catch(DictionaryFailure e){System.err.println(json(Map.of("code",e.code)));return e.code.contains("QUALITY")||e.code.equals("LOCAL_MVP_OPT_IN_REQUIRED")?3:e.code.startsWith("LEASE")||e.code.startsWith("ACTIVE")||e.code.startsWith("DATASET")||e.code.startsWith("DATABASE")||e.code.equals("RESUME_REQUIRED")?4:2;}
        catch(org.springframework.dao.DataAccessException e){System.err.println("{\"code\":\"DATABASE_FAILED\"}");return 4;}
        catch(IllegalArgumentException e){System.err.println("{\"code\":\"INPUT_INVALID\"}");return 2;}
        catch(Exception e){System.err.println("{\"code\":\"TOOL_FAILED\"}");return 5;}
    }
}
