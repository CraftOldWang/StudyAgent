import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class ChunkTokenLengths {
    public static void main(String[] args) throws Exception {
        var encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", 2);
            String text = new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8);
            System.out.println(fields[0] + "\t" + encoding.countTokensOrdinary(text));
        }
    }
}
