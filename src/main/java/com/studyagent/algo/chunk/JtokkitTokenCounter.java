package com.studyagent.algo.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.Objects;

/**
 * 基于 jtokkit CL100K_BASE 的确定性本地计数器。
 */
public final class JtokkitTokenCounter implements TokenCounter {

    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    @Override
    public int count(String text) {
        return ENCODING.countTokensOrdinary(Objects.requireNonNull(text, "text"));
    }
}
