package dev.famer.classic.ticketing

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.runner.options.OptionsBuilder
import org.openjdk.jmh.runner.{Runner, RunnerException}

import java.util.concurrent.TimeUnit
import scala.collection.mutable;

@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@Warmup(iterations = 1)
@Measurement(iterations = 2, time = 1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 2)
@Threads(8)
@State(Scope.Benchmark)
@OperationsPerInvocation
class SleepBenchmark {

  @Param(Array("1", "10", "100"))
  private val n = 0

  @Setup
  def setup(): Unit = {}

  @TearDown
  def tearDown(): Unit = {}

  @Benchmark
  def testStringAdd(blackhole: Blackhole): Unit = {
    var s = ""
    for (i <- 0 until n) {
      s += i
    }
    blackhole.consume(s)
  }

  @Benchmark
  def testStringBuilderAdd(blackhole: Blackhole): Unit = {
    val sb = new mutable.StringBuilder
    for (i <- 0 until n) {
      sb.append(i)
    }
    blackhole.consume(sb.toString)
  }

  @throws[RunnerException]
  def main(args: Array[String]): Unit = {
    val opt = new OptionsBuilder().include(classOf[Nothing].getSimpleName).build
    new Runner(opt).run
  }
}
