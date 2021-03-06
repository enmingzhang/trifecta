package com.github.ldaniels528.trifecta

import com.github.ldaniels528.commons.helpers.OptionHelper._
import com.github.ldaniels528.commons.helpers.StringHelper._
import com.github.ldaniels528.trifecta.command.parser.CommandParser
import com.github.ldaniels528.trifecta.io.{MessageInputSource, MessageOutputSource}
import com.github.ldaniels528.trifecta.messages.query.parser.KafkaQueryParser
import com.github.ldaniels528.trifecta.messages.{CompositeTxDecoder, MessageCodecs, MessageDecoder}
import com.github.ldaniels528.trifecta.modules.ModuleHelper._
import com.github.ldaniels528.trifecta.modules._
import com.github.ldaniels528.trifecta.modules.azure.AzureModule
import com.github.ldaniels528.trifecta.modules.cassandra.CassandraModule
import com.github.ldaniels528.trifecta.modules.core.CoreModule
import com.github.ldaniels528.trifecta.modules.elasticsearch.ElasticSearchModule
import com.github.ldaniels528.trifecta.modules.etl.ETLModule
import com.github.ldaniels528.trifecta.modules.kafka.KafkaModule
import com.github.ldaniels528.trifecta.modules.mongodb.MongoModule
import com.github.ldaniels528.trifecta.modules.zookeeper.ZookeeperModule
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.util.Try

/**
  * Trifecta Runtime Context
  * @author lawrence.daniels@gmail.com
  */
case class TxRuntimeContext(config: TxConfig)(implicit ec: ExecutionContext) {
  private[trifecta] val logger = LoggerFactory.getLogger(getClass)
  private implicit val cfg = config

  // create the result handler
  private val resultHandler = new TxResultHandler(config)

  // support registering decoders
  private val decoders = TrieMap[String, MessageDecoder[_]]()
  private var once = true

  // load the default decoders
  config.getDecoders foreach { txDecoder =>
    txDecoder.decoder match {
      case Left(decoder) => decoders += txDecoder.topic -> decoder
      case _ =>
    }
  }

  // create the module manager and load the built-in modules
  val moduleManager = new ModuleManager()(this)
  moduleManager ++= Seq(
    new AzureModule(config),
    new CassandraModule(config),
    new CoreModule(config),
    new ElasticSearchModule(config),
    new ETLModule(config),
    new KafkaModule(config),
    new MongoModule(config),
    new ZookeeperModule(config))

  // set the "active" module
  moduleManager.findModuleByName("core") foreach moduleManager.setActiveModule

  /**
    * Attempts to resolve the given topic or decoder URL into an actual message decoder
    * @param topicOrUrl the given topic or decoder URL
    * @return an option of a [[MessageDecoder]]
    */
  def resolveDecoder(topicOrUrl: String)(implicit rt: TxRuntimeContext): Option[MessageDecoder[_]] = {
    if (once) {
      config.getDecoders.filter(_.decoder.isLeft).groupBy(_.topic) foreach { case (topic, txDecoders) =>
        rt.registerDecoder(topic, new CompositeTxDecoder(txDecoders))
      }
      once = !once
    }
    decoders.get(topicOrUrl) ?? MessageCodecs.getDecoder(config, topicOrUrl)
  }

  /**
    * Returns the input handler for the given output URL
    * @param url the given input URL (e.g. "es:/quotes/quote/GDF")
    * @return an option of an [[MessageInputSource]]
    */
  def getInputHandler(url: String): Option[MessageInputSource] = {
    // get just the prefix
    val (prefix, _) = parseSourceURL(url).orDie(s"Malformed input source URL: $url")

    // locate the module
    moduleManager.findModuleByPrefix(prefix) flatMap (_.getInputSource(url))
  }

  /**
    * Returns the output handler for the given output URL
    * @param url the given output URL (e.g. "es:/quotes/$exchange/$symbol")
    * @return an option of an [[MessageOutputSource]]
    */
  def getOutputHandler(url: String): Option[MessageOutputSource] = {
    // get just the prefix
    val (prefix, _) = parseSourceURL(url).orDie(s"Malformed output source URL: $url")

    // locate the module
    moduleManager.findModuleByPrefix(prefix) flatMap (_.getOutputSource(url))
  }

  def handleResult(result: Any, input: String)(implicit ec: ExecutionContext) = {
    resultHandler.handleResult(result, input)
  }

  def interpret(input: String): Try[Any] = {
    input.trim match {
      case s if s.startsWith("`") && s.endsWith("`") => executeCommand(s.drop(1).dropRight(1))
      case s => interpretCommandLine(s)
    }
  }

  /**
    * Attempts to retrieve a message decoder by name
    * @param name the name of the desired [[MessageDecoder]]
    * @return an option of a [[MessageDecoder]]
    */
  def lookupDecoderByName(name: String): Option[MessageDecoder[_]] = decoders.get(name)

  /**
    * Registers a message decoder, which can be later retrieved by name
    * @param name    the name of the [[MessageDecoder]]
    * @param decoder the [[MessageDecoder]] instance
    */
  def registerDecoder(name: String, decoder: MessageDecoder[_]): Unit = decoders(name) = decoder

  /**
    * Releases all resources
    */
  def shutdown(): Unit = {
    logger.info("Shutting down...")
    moduleManager.shutdown()
  }

  /**
    * Executes a local system command
    * @example `ps -ef`
    */
  private def executeCommand(command: String): Try[String] = {
    import scala.sys.process._

    Try(command.!!)
  }

  def getDeviceURLWithDefault(prefix: String, deviceURL: String): String = {
    if (deviceURL.contains(':')) deviceURL else s"$prefix:$deviceURL"
  }

  /**
    * Interprets command line input
    * @param input the given line of input
    * @return a try-monad wrapped result
    */
  private def interpretCommandLine(input: String) = Try {
    // is the input a query?
    if (input.startsWith("select")) KafkaQueryParser(input).executeQuery(this)
    else {
      // parse the input into tokens
      val tokens = CommandParser.parseTokens(input)

      // convert the tokens into Unix-style arguments
      val unixArgs = CommandParser.parseUnixLikeArgs(tokens)

      // match the command
      val commandSet = moduleManager.commandSet

      for {
        commandName <- unixArgs.commandName
        command = commandSet.getOrElse(commandName, die(s"command '$commandName' not found"))

      } yield {
        // verify and execute the command
        command.params.checkArgs(command, tokens)
        val result = command.fx(unixArgs)

        // auto-switch modules?
        if (config.autoSwitching && (command.promptAware || command.module.moduleName != "core")) {
          moduleManager.setActiveModule(command.module)
        }
        result
      }
    }
  }

  /**
    * Parses the the prefix and path from the I/O source URL
    * @param url the I/O source URL
    * @return the tuple represents the prefix and path
    */
  private def parseSourceURL(url: String) = url.indexOptionOf(":") map url.splitAt

}
