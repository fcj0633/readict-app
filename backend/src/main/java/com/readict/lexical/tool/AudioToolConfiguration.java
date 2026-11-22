package com.readict.lexical.tool;

import com.readict.lexical.audio.*;
import org.springframework.context.annotation.Import;

/** Explicit offline application; not component-scanned by the Web application. */
@Import({DictionaryToolConfiguration.class,AudioImporter.class,AudioStorage.class,AudioCatalog.class})
public class AudioToolConfiguration {}
