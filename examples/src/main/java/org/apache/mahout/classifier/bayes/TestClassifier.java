/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.classifier.bayes;

import org.apache.mahout.classifier.ClassifierResult;

import org.apache.mahout.classifier.ResultAnalyzer;
import org.apache.mahout.classifier.bayes.algorithm.BayesAlgorithm;
import org.apache.mahout.classifier.bayes.algorithm.CBayesAlgorithm;
import org.apache.mahout.classifier.bayes.common.BayesParameters;
import org.apache.mahout.classifier.bayes.datastore.HBaseBayesDatastore;
import org.apache.mahout.classifier.bayes.datastore.InMemoryBayesDatastore;
import org.apache.mahout.classifier.bayes.exceptions.InvalidDatastoreException;
import org.apache.mahout.classifier.bayes.interfaces.Algorithm;
import org.apache.mahout.classifier.bayes.interfaces.Datastore;
import org.apache.mahout.classifier.bayes.mapreduce.bayes.BayesClassifierDriver;
import org.apache.mahout.classifier.bayes.model.ClassifierContext;
import org.apache.mahout.common.CommandLineUtil;
import org.apache.mahout.common.TimingStatistics;
import org.apache.mahout.common.commandline.DefaultOptionCreator;
import org.apache.mahout.common.nlp.NGrams;
import org.apache.mahout.common.FileLineIterable;
import org.apache.commons.cli2.Option;
import org.apache.commons.cli2.CommandLine;
import org.apache.commons.cli2.Group;
import org.apache.commons.cli2.OptionException;
import org.apache.commons.cli2.commandline.Parser;
import org.apache.commons.cli2.builder.DefaultOptionBuilder;
import org.apache.commons.cli2.builder.ArgumentBuilder;
import org.apache.commons.cli2.builder.GroupBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.nio.charset.Charset;

/**
 * Test the Naive Bayes classifier with improved weighting
 * <p/>
 * To run the twenty newsgroups example: refer
 * http://cwiki.apache.org/MAHOUT/twentynewsgroups.html
 */
public class TestClassifier {

  private static final Logger log = LoggerFactory
      .getLogger(TestClassifier.class);

  private TestClassifier() {
    // do nothing
  }

  public static void main(String[] args) throws IOException, InvalidDatastoreException {
    DefaultOptionBuilder obuilder = new DefaultOptionBuilder();
    ArgumentBuilder abuilder = new ArgumentBuilder();
    GroupBuilder gbuilder = new GroupBuilder();

    Option pathOpt = obuilder
        .withLongName("model")
        .withRequired(true)
        .withArgument(
            abuilder.withName("model").withMinimum(1).withMaximum(1).create())
        .withDescription(
            "The path on HDFS / Name of Hbase Table as defined by the -source parameter")
        .withShortName("m").create();

    Option dirOpt = obuilder
        .withLongName("testDir")
        .withRequired(true)
        .withArgument(
            abuilder.withName("testDir").withMinimum(1).withMaximum(1).create())
        .withDescription("The directory where test documents resides in")
        .withShortName("d").create();

    Option helpOpt = DefaultOptionCreator.helpOption(obuilder);

    Option encodingOpt = obuilder.withLongName("encoding").withArgument(
        abuilder.withName("encoding").withMinimum(1).withMaximum(1).create())
        .withDescription("The file encoding.  Defaults to UTF-8")
        .withShortName("e").create();

    Option defaultCatOpt = obuilder.withLongName("defaultCat").withArgument(
        abuilder.withName("defaultCat").withMinimum(1).withMaximum(1).create())
        .withDescription("The default category Default Value: unknown")
        .withShortName("default").create();

    Option gramSizeOpt = obuilder.withLongName("gramSize").withRequired(true)
        .withArgument(
            abuilder.withName("gramSize").withMinimum(1).withMaximum(1)
                .create()).withDescription(
            "Size of the n-gram. Default Value: 1").withShortName("ng")
        .create();

    Option alphaOpt = obuilder.withLongName("alpha").withRequired(false)
        .withArgument(
            abuilder.withName("a").withMinimum(1).withMaximum(1).create())
        .withDescription("Smoothing parameter Default Value: 1.0")
        .withShortName("a").create();

    Option verboseOutputOpt = obuilder.withLongName("verbose").withRequired(
        false).withDescription(
        "Output which values were correctly and incorrectly classified")
        .withShortName("v").create();

    Option typeOpt = obuilder.withLongName("classifierType").withRequired(true)
        .withArgument(
            abuilder.withName("classifierType").withMinimum(1).withMaximum(1)
                .create()).withDescription(
            "Type of classifier: bayes|cbayes. Default Value: bayes")
        .withShortName("type").create();

    Option dataSourceOpt = obuilder.withLongName("dataSource").withRequired(
        true).withArgument(
        abuilder.withName("dataSource").withMinimum(1).withMaximum(1).create())
        .withDescription("Location of model: hdfs|hbase Default Value: hdfs")
        .withShortName("source").create();

    Option methodOpt = obuilder
        .withLongName("method")
        .withRequired(true)
        .withArgument(
            abuilder.withName("method").withMinimum(1).withMaximum(1).create())
        .withDescription(
            "Method of Classification: sequential|mapreduce. Default Value: sequential")
        .withShortName("method").create();

    Group group = gbuilder.withName("Options").withOption(defaultCatOpt)
        .withOption(dirOpt).withOption(encodingOpt).withOption(gramSizeOpt)
        .withOption(pathOpt).withOption(typeOpt).withOption(dataSourceOpt)
        .withOption(helpOpt).withOption(methodOpt).withOption(verboseOutputOpt)
        .withOption(alphaOpt).create();

    try {
      Parser parser = new Parser();
      parser.setGroup(group);
      CommandLine cmdLine = parser.parse(args);

      if (cmdLine.hasOption(helpOpt)) {
        CommandLineUtil.printHelp(group);
        return;
      }

      int gramSize = 1;
      if (cmdLine.hasOption(gramSizeOpt)) {
        gramSize = Integer.parseInt((String) cmdLine.getValue(gramSizeOpt));

      }
      BayesParameters params = new BayesParameters(gramSize);

      String modelBasePath = (String) cmdLine.getValue(pathOpt);

      String classifierType = (String) cmdLine.getValue(typeOpt);
      String dataSource = (String) cmdLine.getValue(dataSourceOpt);

      String defaultCat = "unknown";
      if (cmdLine.hasOption(defaultCatOpt)) {
        defaultCat = (String) cmdLine.getValue(defaultCatOpt);
      }

      String encoding = "UTF-8";
      if (cmdLine.hasOption(encodingOpt)) {
        encoding = (String) cmdLine.getValue(encodingOpt);
      }

      String alpha_i = "1.0";
      if (cmdLine.hasOption(alphaOpt)) {
        alpha_i = (String) cmdLine.getValue(alphaOpt);
      }

      boolean verbose = cmdLine.hasOption(verboseOutputOpt);

      String testDirPath = (String) cmdLine.getValue(dirOpt);

      String classificationMethod = (String) cmdLine.getValue(methodOpt);

      params.set("verbose", Boolean.toString(verbose));
      params.set("basePath", modelBasePath);
      params.set("classifierType", classifierType);
      params.set("dataSource", dataSource);
      params.set("defaultCat", defaultCat);
      params.set("encoding", encoding);
      params.set("alpha_i", alpha_i);
      params.set("testDirPath", testDirPath);

      if (classificationMethod.equalsIgnoreCase("sequential"))
        classifySequential(params);
      else if (classificationMethod.equalsIgnoreCase("mapreduce"))
        classifyParallel(params);
    } catch (OptionException e) {
      CommandLineUtil.printHelp(group);
      return;
    }
  }

  public static void classifySequential(BayesParameters params)
      throws IOException, InvalidDatastoreException {
    log.info("Loading model from: {}", params.print());
    boolean verbose = Boolean.valueOf(params.get("verbose"));
    File dir = new File(params.get("testDirPath"));
    File[] subdirs = dir.listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File file, String s) {
        return s.startsWith(".") == false;
      }
    });

    Algorithm algorithm;
    Datastore datastore;

    if (params.get("dataSource").equals("hdfs")) {
      if (params.get("classifierType").equalsIgnoreCase("bayes")) {
        log.info("Testing Bayes Classifier");
        algorithm = new BayesAlgorithm();
        datastore = new InMemoryBayesDatastore(params);
      } else if (params.get("classifierType").equalsIgnoreCase("cbayes")) {
        log.info("Testing Complementary Bayes Classifier");
        algorithm = new CBayesAlgorithm();
        datastore = new InMemoryBayesDatastore(params);
      } else {
        throw new IllegalArgumentException("Unrecognized classifier type: "
            + params.get("classifierType"));
      }

    } else if (params.get("dataSource").equals("hbase")) {
      if (params.get("classifierType").equalsIgnoreCase("bayes")) {
        log.info("Testing Bayes Classifier");
        algorithm = new BayesAlgorithm();
        datastore = new HBaseBayesDatastore(params.get("basePath"), params);
      } else if (params.get("classifierType").equalsIgnoreCase("cbayes")) {
        log.info("Testing Complementary Bayes Classifier");
        algorithm = new CBayesAlgorithm();
        datastore = new HBaseBayesDatastore(params.get("basePath"), params);
      } else {
        throw new IllegalArgumentException("Unrecognized classifier type: "
            + params.get("classifierType"));
      }

    } else {
      throw new IllegalArgumentException("Unrecognized dataSource type: "
          + params.get("dataSource"));
    }
    ClassifierContext classifier = new ClassifierContext(algorithm, datastore);
    classifier.initialize();
    ResultAnalyzer resultAnalyzer = new ResultAnalyzer(classifier.getLabels(),
        params.get("defaultCat"));
    final TimingStatistics totalStatistics = new TimingStatistics();
    if (subdirs != null) {

      for (File file : subdirs) {
        log.info("--------------");
        log.info("Testing: " + file);
        String correctLabel = file.getName().split(".txt")[0];
        final TimingStatistics operationStats = new TimingStatistics();

        long lineNum = 0;
        for (String line : new FileLineIterable(new File(file.getPath()),
            Charset.forName(params.get("encoding")), false)) {

          Map<String, List<String>> document = new NGrams(line, Integer
              .parseInt(params.get("gramSize"))).generateNGrams();
          for (Map.Entry<String, List<String>> stringListEntry : document
              .entrySet()) {
            List<String> strings = stringListEntry.getValue();
            TimingStatistics.Call call = operationStats.newCall();
            TimingStatistics.Call outercall = totalStatistics.newCall();
            ClassifierResult classifiedLabel = classifier.classifyDocument(
                strings.toArray(new String[strings.size()]), params
                    .get("defaultCat"));
            call.end();
            outercall.end();
            boolean correct = resultAnalyzer.addInstance(correctLabel,
                classifiedLabel);
            if (verbose) {
              // We have one document per line
              log.info("Line Number: " + lineNum + " Line(30): "
                  + (line.length() > 30 ? line.substring(0, 30) : line)
                  + " Expected Label: " + correctLabel + " Classified Label: "
                  + classifiedLabel.getLabel() + " Correct: " + correct);
            }
            // log.info("{} {}", correctLabel, classifiedLabel);

          }
          lineNum++;
        }
        log.info("{}\t{}\t{}/{}", new Object[] { correctLabel,
            resultAnalyzer.getConfusionMatrix().getAccuracy(correctLabel),
            resultAnalyzer.getConfusionMatrix().getCorrect(correctLabel),
            resultAnalyzer.getConfusionMatrix().getTotal(correctLabel) });
        log.info("{}", operationStats.toString());
      }

    }
    log.info("{}", totalStatistics.toString());
    log.info(resultAnalyzer.summarize());
  }

  public static void classifyParallel(BayesParameters params)
      throws IOException {
    BayesClassifierDriver.runJob(params);
  }
}