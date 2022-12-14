package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final ForkJoinPool pool;
  private final int maxDepth;
  private final PageParserFactory parserFactory;
  private final List<Pattern> ignoredUrl;
  @Inject
  ParallelWebCrawler(
      Clock clock,
      @Timeout Duration timeout,
      @PopularWordCount int popularWordCount,
      @TargetParallelism int threadCount,
      @IgnoredUrls List<Pattern> ignoredUrl,
      @MaxDepth int maxDepth,
      PageParserFactory parserFactory) {
    this.clock = clock;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
    this.ignoredUrl = ignoredUrl;
    this.maxDepth = maxDepth;
    this.parserFactory = parserFactory;
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadline = clock.instant().plus(timeout);
    ConcurrentMap<String, Integer> counts = new ConcurrentHashMap<>();
    ConcurrentSkipListSet<String> visitedUrl = new ConcurrentSkipListSet<>();
    for (String url: startingUrls) {
      pool.invoke(new InternalCrawler(url, deadline, maxDepth, counts, visitedUrl));
    }
    if (counts.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(counts)
              .setUrlsVisited(visitedUrl.size())
              .build();
    }
    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counts, popularWordCount))
            .setUrlsVisited(visitedUrl.size())
            .build();
  }

  public class InternalCrawler extends RecursiveTask<Boolean> {
    private final String url;
    private final Instant deadline;
    private final int maxDepth;
    private final ConcurrentMap<String, Integer> counts;
    private final ConcurrentSkipListSet<String> visitedUrl;

    public InternalCrawler(String url, Instant deadline, int maxDepth,
                           ConcurrentMap<String, Integer> counts,
                           ConcurrentSkipListSet<String> visitedUrl) {
      this.url = url;
      this.deadline = deadline;
      this.maxDepth = maxDepth;
      this.counts = counts;
      this.visitedUrl = visitedUrl;
    }

    @Override
    protected Boolean compute() {
      if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
        return false;
      }
      for (Pattern pattern: ignoredUrl) {
        if (pattern.matcher(url).matches()) {
          return false;
        }
      }
      if (!visitedUrl.add(url)){
        return false;
      }
      PageParser.Result result = parserFactory.get(url).parse();

      for (Map.Entry<String, Integer> e: result.getWordCounts().entrySet()) {
        counts.compute(e.getKey(),(k, v) -> (v == null) ? e.getValue() : e.getValue() + v);
      }
      List<InternalCrawler> subtasks = new ArrayList<>();
      for (String link: result.getLinks()) {
        subtasks.add(new InternalCrawler(link, deadline, maxDepth - 1, counts, visitedUrl));
      }
      invokeAll(subtasks);
      return true;
    }
  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }
}
