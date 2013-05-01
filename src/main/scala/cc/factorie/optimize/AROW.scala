package cc.factorie.optimize
import cc.factorie._
import cc.factorie.la._
import cc.factorie.la.Tensor
import cc.factorie.maths

// TODO Change this so it doesn't require the "model" constructor argument, and instead gets the weights from the "step" call.
// Then AROW can be the default optimizer in SGDTrainer.
class AROW(model:Weights, val lambdaAROW:Double=1.0) extends ConfidenceWeighting(model) {
  //parameters specific to the algorithm
  // TODO this shouldn't be 1-modelScore as it assumes a margin of 1 - should probably put 0 here now that margin is in objective - luke
  protected def alpha(modelScore:Double,gradient:Tensors) : Double = math.max(0,1-modelScore) * beta(gradient)
  protected def beta(gradient:Tensors) : Double = 1/(marginVariance(gradient) + 2*lambdaAROW)
  override def adjustConfidence(weights:Tensors, gradient:Tensors):Unit ={
    val betaVal = beta(gradient)
    for(template <- gradient.keys){ //Update per-parameter learning rates.
      val templateWeights = weights(template)
      val templateGradient = gradient(template)
      val templateLearningRates = sigma(template)
      templateGradient.foreachActiveElement((index,value) => {
        templateLearningRates(index)=
          templateLearningRates(index)
        - betaVal*templateLearningRates(index)*templateLearningRates(index)*value*value
      })
    }
  }
  override def calculateLearningRate(gradient: Tensors,margin:Double): Double = alpha(margin,gradient)
}

class ConfidenceWeighting(val model:Weights) extends GradientOptimizer {
  /**Initialize the diagonal covariance matrix; this is the value in the diagonal elements */
  var initialVariance = 0.1
  var numUpdates : Double = 0
  protected var learningRate : Double = 1 //adjusted automatically by CW
  val zeroEpsilon = 0.0000001 //used to detect when something is approximately zero
  protected var eta = 0.98; //confidence value, between 0.5 and 1
  var gaussDeviate = maths.probit(eta) //function of the confidence, it is more intuitive to express in terms of eta
  def isConverged=false
  def reset(): Unit = {
    println("Resetting CW")
    gaussDeviate = maths.probit(eta)
    for(template <- sigma.keys)sigma(template) := initialVariance
  }
  def setConfidence(aeta:Double) : Unit = {
    assert(aeta > 0.5 && aeta < 1.0)
    eta = aeta
    gaussDeviate = maths.probit(eta)
  }
  lazy val sigma:Tensors = {
    //print("Initializing sigma...")
    val result:Tensors = model.weightsTensor.blankDenseCopy
    for(template <- result.keys) result(template) := initialVariance
    //println(" done.")
    result
  }
  def step(weights:Tensors, gradient:Tensors, value:Double): Unit = {
    //println("Performing step")
    gradient match{
      case gradient:Tensors => {
        weights match{
          case weights:Tensors =>{
            learningRate = calculateLearningRate(gradient, value)
            gradient.keys.foreach(k => weights(k).+=(gradient(k),sigma(k),learningRate))
            adjustConfidence(weights,gradient)
          }
          case _ => throw new Exception("Confidence weighting only implemented for weight vectors of type WeightsTensor.")
        }
      }
      case _ => throw new Exception("Confidence weighting only implemented for gradients of type WeightsTensor.")
    }
  }
  def adjustConfidence(weights:Tensors, gradient:Tensors):Unit ={
    for(template <- gradient.keys){ //Update per-parameter learning rates.
      val templateWeights = weights(template)
      val templateGradient = gradient(template)
      val templateLearningRates = sigma(template)
      (templateGradient, templateLearningRates) match {
        case (tg: DenseTensor, tlr: DenseTensor) =>
          val tgArr = tg.asArray
          val tlrArr = tlr.asArray
          var i = 0
          while (i < tgArr.length) {
            val value = tgArr(i)
            tlrArr(i) = 1.0/((1.0/tlrArr(i))
              + 2*learningRate*gaussDeviate*value*value)
            i += 1
          }
        case _ =>
          val gradIter = templateGradient.activeElements
          while (gradIter.hasNext) {
            val (index, value) = gradIter.next()
            templateLearningRates(index) = 1.0/((1.0/templateLearningRates(index))
              + 2*learningRate*gaussDeviate*value*value)
          }
      }
    }
  }
  def calculateLearningRate(gradient: Tensors, margin:Double): Double = {
    val marginMean = margin.abs
    val v = 1.0 + 2.0 * gaussDeviate * marginMean
    val marginVar = marginVariance(gradient)
    var lambda = 0.0
    if (marginMean >= gaussDeviate * marginVar)
      return 0.0
    if (marginVar > zeroEpsilon || marginVar < -zeroEpsilon)
      lambda = (-v + math.sqrt(v * v - 8 * gaussDeviate * (marginMean - gaussDeviate * marginVar))) / (4 * gaussDeviate * marginVar)
    math.max(0, lambda)
  }
  def marginVariance(gradient:Tensors):Double ={
    var result = 0.0
    for(template <- gradient.keys){
      val templateLearningRates=sigma(template)
      (templateLearningRates, gradient(template)) match {
        case (tlr: DenseTensor, grad: DenseTensor) =>
          val gradArr = grad.asArray
          val tlrArr = tlr.asArray
          var index = 0
          var res = 0.0
          while (index < gradArr.length) {
            val value = gradArr(index)
            res += value * value * tlrArr(index)
            index += 1
          }
          result = res
        case (tlr: DenseTensor3,  grad:Dense2LayeredTensorLike3) =>
          var i = 0
          var res = 0.0
          while (i < grad.dim1) {
            var j = 0
            while (j < grad.dim2) {
              grad.inner(i, j) match {
                case g: DenseTensor =>
                  var k = 0
                  while (k < grad.dim3) {
                    res += g(k) * g(k) * tlr(i, j, k)
                    k += 1
                  }
                case g: SparseTensor1 =>
                  g.activeElements.foreach(x => {
                    res += x._2 * x._2 * tlr(i, j, x._1)
                  })
              }
              j += 1
            }
            i += 1
            result = res
          }
        case _ =>
          gradient(template).foreachActiveElement((index,value) => result += value*value*templateLearningRates(index))
      }

    }
    result
  }
}