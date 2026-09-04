package io.github.xw66.knowflowai.retrieval;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RrfTests {
    @Test void fusionUsesRanksInsteadOfRawScoresAndTruncatesOnlyAfterMerging() {
        var vector=List.of(hit(1,0.999),hit(2,0.001));
        var lexical=List.of(hit(3,1000),hit(2,1));
        var result=SearchService.fuse(vector,lexical,1);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().chunkId()).isEqualTo(2);
        assertThat(result.getFirst().score()).isEqualTo(2.0/62);
        assertThat(result.getFirst().content()).isEqualTo("原文2");
    }

    @Test void duplicateCandidatesCountOnlyOncePerRouteAndTiesUseChunkId() {
        var result=SearchService.fuse(List.of(hit(9,0.9),hit(9,0.8),hit(2,0.1)),
                List.of(hit(2,100),hit(9,2)),20);
        assertThat(result).extracting(SearchService.Hit::chunkId).containsExactly(2L,9L);
        assertThat(result).allSatisfy(hit -> assertThat(hit.score()).isCloseTo(1.0/61+1.0/62,within(1e-15)));
    }

    @Test void emptyRouteIsValidAndEmptyResultsStayEmpty() {
        assertThat(SearchService.fuse(List.of(),List.of(),5)).isEmpty();
        var result=SearchService.fuse(List.of(),List.of(hit(2,999)),5);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().score()).isEqualTo(1.0/61);
    }

    private SearchService.Hit hit(long id,double score) {
        return new SearchService.Hit(id,id,"文档"+id,"原文"+id,null,1,score);
    }
}
