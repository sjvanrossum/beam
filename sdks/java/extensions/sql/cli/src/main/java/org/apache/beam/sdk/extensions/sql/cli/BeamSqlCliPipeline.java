package org.apache.beam.sdk.extensions.sql.cli;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.sql.impl.BeamSqlEnv;
import org.apache.beam.sdk.extensions.sql.impl.BeamSqlPipelineOptions;
import org.apache.beam.sdk.extensions.sql.impl.rel.BeamSqlRelUtils;
import org.apache.beam.sdk.extensions.sql.meta.provider.TableProvider;
import org.apache.beam.sdk.extensions.sql.meta.store.InMemoryMetaStore;
import org.apache.beam.sdk.extensions.sql.meta.store.MetaStore;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.EmptyMatchTreatment;
import org.apache.beam.sdk.io.fs.MatchResult;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.grpc.v1p26p0.com.google.common.collect.Iterables;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Strings;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BeamSqlCliPipeline {

  private static final Logger LOG = LoggerFactory.getLogger(BeamSqlCliPipeline.class);

  public interface BeamSqlCliPipelineOptions extends PipelineOptions {

    @Description("Files containing DDL statements to create table definitions.")
    List<String> getDdlPaths();

    void setDdlPaths(List<String> ddlPaths);

    @Description("DDL statements to create table definitions.")
    List<String> getDdls();

    void setDdls(List<String> ddls);

    @Description("File containing a single SQL query to create the pipeline logic.")
    String getQueryPath();

    void setQueryPath(String queryPath);

    @Description("SQL query to create the pipeline logic.")
    String getQuery();

    void setQuery(String query);

    @Description("Required. Output table name.")
    @Validation.Required
    String getOutputTableName();

    void setOutputTableName(String outputTableName);
  }

  private static List<String> readFiles(List<String> paths) {
    try {
      return FileSystems.match(paths, EmptyMatchTreatment.ALLOW)
          .stream()
          .flatMap(matchResult -> {
            try {
              return matchResult.metadata().stream();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          })
          .map(MatchResult.Metadata::resourceId)
          .map(resourceId -> {
            try (Reader reader = Channels.newReader(FileSystems.open(resourceId), StandardCharsets.UTF_8.name())) {
              return CharStreams.toString(reader);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }).collect(Collectors.toList());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void validateArgs(BeamSqlCliPipelineOptions options) {
    if ((options.getDdls() == null || options.getDdls().isEmpty()) && (options.getDdlPaths() == null || options.getDdlPaths().isEmpty())) {
      throw new IllegalArgumentException("Either ddls or ddlPaths should be set, but found none");
    }

    if (!(options.getDdls() == null || options.getDdls().isEmpty()) && !(options.getDdlPaths() == null || options.getDdlPaths().isEmpty())) {
      throw new IllegalArgumentException("Either ddls or ddlPaths should be set, but found both");
    }

    if (Strings.isNullOrEmpty(options.getQuery()) && Strings.isNullOrEmpty(options.getQueryPath())) {
      throw new IllegalArgumentException("Either query or queryPath should be set, but found none");
    }

    if (!Strings.isNullOrEmpty(options.getQuery()) && !Strings.isNullOrEmpty(options.getQueryPath())) {
      throw new IllegalArgumentException("Either query or queryPath should be set, but found both");
    }
  }

  public static void main(String[] args) throws IOException {
    BeamSqlCliPipelineOptions options = PipelineOptionsFactory.fromArgs(args).as(BeamSqlCliPipelineOptions.class);
    FileSystems.setDefaultPipelineOptions(options);
    validateArgs(options);

    MetaStore metaStore = new InMemoryMetaStore();
    for (TableProvider provider :
        ServiceLoader.load(TableProvider.class, BeamSqlCliPipeline.class.getClassLoader())) {
      metaStore.registerProvider(provider);
    }

    BeamSqlEnv ddlEnv = BeamSqlEnv.builder(metaStore)
        .autoLoadBuiltinFunctions()
        .autoLoadUserDefinedFunctions()
        .setPipelineOptions(options)
        .setQueryPlannerClassName(options.as(BeamSqlPipelineOptions.class).getPlannerName())
        .build();

    List<String> ddls = !(options.getDdls() == null || options.getDdls().isEmpty()) ? options.getDdls() : readFiles(options.getDdlPaths());
    String sql = !Strings.isNullOrEmpty(options.getQuery()) ? options.getQuery() : Iterables.getOnlyElement(readFiles(ImmutableList.of(options.getQueryPath())));

    for (String ddl : ddls) {
      ddlEnv.executeDdl(ddl);
    }

    BeamSqlCliPipelineOptions combinedOptions = PipelineOptionsFactory.fromArgs(Stream.concat(Arrays.stream(args), ddlEnv.getPipelineOptions().entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue())).toArray(String[]::new)).as(BeamSqlCliPipelineOptions.class);

    BeamSqlEnv sqlEnv = BeamSqlEnv.builder(metaStore)
        .autoLoadBuiltinFunctions()
        .autoLoadUserDefinedFunctions()
        .setPipelineOptions(combinedOptions)
        .setQueryPlannerClassName(combinedOptions.as(BeamSqlPipelineOptions.class).getPlannerName())
        .build();

    Pipeline p = Pipeline.create(combinedOptions);
    PCollection<Row> rows = BeamSqlRelUtils.toPCollection(p, sqlEnv.parseQuery(sql));
    metaStore.buildBeamSqlTable(metaStore.getTable(options.getOutputTableName())).buildIOWriter(rows);
    p.run().waitUntilFinish();
  }
}
