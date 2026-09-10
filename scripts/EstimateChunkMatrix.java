import com.studyagent.algo.chunk.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Local sizing only: reuse the application's chunker without model or index calls. */
public class EstimateChunkMatrix {
    public static void main(String[] args) throws Exception {
        var counter = new JtokkitTokenCounter();
        var structured = new StructuredChunker(counter);
        var window = new TokenWindowChunker(counter);
        var documents = new ArrayList<List<ChunkSegment>>();
        long sourceTokens = 0;
        for (String file : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String text = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            sourceTokens += counter.count(text);
            documents.add(structured.parentChunks(text, 2400));
        }
        System.out.println("source_tokens=" + sourceTokens);
        System.out.println("size\toverlap\tparents\tchildren\tinput_tokens\tunique_inputs\tnew_inputs_across_configs\tnew_tokens_across_configs\trequests_if_20_per_document");
        var seen = new HashSet<String>();
        for (int[] config : new int[][]{{400,40}, {800,80}, {1200,120}, {800,0}, {800,160}}) {
            int parents = 0, children = 0, newInputs = 0, batchRequests = 0;
            long tokens = 0, newTokens = 0;
            var unique = new HashSet<String>();
            for (var document : documents) {
                int docChildren = 0;
                parents += document.size();
                for (var parent : document) {
                    for (var child : window.splitStructured(parent, config[0], config[1])) {
                        children++; docChildren++; tokens += child.tokenCount();
                        unique.add(child.content());
                        if (seen.add(child.content())) { newInputs++; newTokens += child.tokenCount(); }
                    }
                }
                batchRequests += (docChildren + 19) / 20;
            }
            System.out.printf(Locale.ROOT, "%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d%n",
                    config[0], config[1], parents, children, tokens, unique.size(), newInputs, newTokens, batchRequests);
        }
    }
}
