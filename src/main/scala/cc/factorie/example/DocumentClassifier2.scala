/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */



package cc.factorie.example

import scala.collection.mutable.{ArrayBuffer}
import scala.util.matching.Regex
import scala.io.Source
import java.io.File
import cc.factorie._
import cc.factorie.optimize._
import cc.factorie.HammingObjective

/** A raw document classifier without using any of the facilities of cc.factorie.app.classify.document,
 and without using the entity-relationship language of cc.factorie.er.  By contrast, see example/DocumentClassifier1. */
object DocumentClassifier2 {

  object DocumentDomain extends CategoricalDimensionTensorDomain[String]
  class Document(file:File) extends BinaryFeatureVectorVariable[String] {
    def domain = DocumentDomain
    var label = new Label(file.getParentFile.getName, this)
    // Read file, tokenize with word regular expression, and add all matches to this BinaryFeatureVectorVariable
    "\\w+".r.findAllIn(Source.fromFile(file).mkString).foreach(regexMatch => this += regexMatch.toString)
  }
  object LabelDomain extends CategoricalDomain[String]
  class Label(name:String, val document:Document) extends LabeledCategoricalVariable(name) {
    def domain = LabelDomain
  }

  val model = new TemplateModel(
    /** Bias term just on labels */
    new DotTemplateWithStatistics1[Label] {
      lazy val weights = new la.DenseTensor1(LabelDomain.size)
    }, 
    /** Factor between label and observed document */
    new DotTemplateWithStatistics2[Label,Document] {
      lazy val weights = new la.DenseTensor2(LabelDomain.size, DocumentDomain.dimensionSize)
      def unroll1 (label:Label) = Factor(label, label.document)
      def unroll2 (token:Document) = throw new Error("Document values shouldn't change")
    }
  )

  val objective = new HammingTemplate[Label]

  def main(args: Array[String]) : Unit = {
    if (args.length < 2) 
      throw new Error("Usage: directory_class1 directory_class2 ...\nYou must specify at least two directories containing text files for classification.")

    // Read data and create Variables
    var documents = new ArrayBuffer[Document];
    for (directory <- args) {
      val directoryFile = new File(directory)
      if (! directoryFile.exists) throw new IllegalArgumentException("Directory "+directory+" does not exist.")
      for (file <- new File(directory).listFiles; if (file.isFile)) {
        //println ("Directory "+directory+" File "+file+" documents.size "+documents.size)
        documents += new Document(file)
      }
    }
    
    // Make a test/train split
    val (testSet, trainSet) = documents.shuffle.split(0.5)
    var trainVariables = trainSet.map(_ label)
    var testVariables = testSet.map(_ label)
    (trainVariables ++ testVariables).foreach(_.setRandomly())

    //println(model)
    //println(model.factors(Seq(trainVariables.head)))

    // Train and test
    val learner = new SampleRankTrainer(new GibbsSampler(model, objective), new MIRA)
    val predictor = new GibbsSampler(model)
    for (i <- 0 until 10) {
      learner.processContexts(trainVariables)
      predictor.processAll(testVariables)
    }
    println ("Train accuracy = "+ HammingObjective.accuracy(trainVariables))
    println ("Test  accuracy = "+ HammingObjective.accuracy(testVariables))

  }
}



