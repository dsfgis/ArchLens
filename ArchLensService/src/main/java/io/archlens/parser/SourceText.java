package io.archlens.parser;

import io.archlens.contract.Json;
import io.archlens.contract.Model.Location;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import static io.archlens.contract.ContractException.require;

public record SourceText(String path,String text,String hash,Location location) {
    public static final long MAX_BYTES=5_000_000;
    public static SourceText read(Path root,String relative) throws IOException {
        Path base=root.toRealPath();
        require(relative!=null && !Path.of(relative).isAbsolute(),"INVALID_PATH","Source path must be relative");
        Path file=base.resolve(relative).normalize().toRealPath();
        require(file.startsWith(base),"PATH_OUTSIDE_ROOT","Source must remain inside input root");
        require(Files.isRegularFile(file) && Files.size(file)<=MAX_BYTES,"FILE_LIMIT","Source exceeds 5 MB or is not a regular file");
        byte[] bytes;
        try(var in=Files.newInputStream(file)) { bytes=in.readNBytes((int)MAX_BYTES+1); }
        require(bytes.length<=MAX_BYTES,"FILE_LIMIT","Source grew beyond limit");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        int line=1,column=1;
        for(int i=0;i<text.length();) {
            int cp=text.codePointAt(i); i+=Character.charCount(cp);
            if(cp=='\r') { if(i<text.length() && text.charAt(i)=='\n') i++; line++; column=1; }
            else if(cp=='\n') { line++; column=1; } else column++;
        }
        String path=base.relativize(file).toString().replace('\\','/');
        return new SourceText(path,text,Json.sha256(bytes),new Location(path,1,1,line,column,0L,(long)bytes.length,null));
    }
}
