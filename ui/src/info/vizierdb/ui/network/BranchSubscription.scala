package info.vizierdb.ui.network

import org.scalajs.dom
import info.vizierdb.ui.API
import rx._
import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.collection.mutable
import scala.concurrent.{ Promise, Future }
import info.vizierdb.ui.rxExtras.RxBufferVar
import info.vizierdb.types._
import scala.scalajs.js.timers._
import info.vizierdb.ui.components.Artifact
import scala.concurrent.ExecutionContext.Implicits.global

class BranchSubscription(branchId: Identifier, projectId: Identifier, api: API)
{
  var socket = getSocket() 
  var awaitingReSync = false
  var keepaliveTimer: SetIntervalHandle = null

  val connected = Var(false)
  val modules = new RxBufferVar[ModuleSubscription]()

  protected[ui] def getSocket(): dom.WebSocket =
  {
    println(s"Connecting to ${api.urls.websocket}")
    val s = new dom.WebSocket(api.urls.websocket)
    s.onopen = onConnected
    s.onclose = onClosed
    s.onerror = onError
    s.onmessage = onMessage
    keepaliveTimer = setInterval(20000) { keepalive(s) }
    s
  }

  private def keepalive(s: dom.WebSocket)
  {
    s.send(
      JSON.stringify(
        js.Dictionary(
          "operation" -> "ping",
        )
      )
    )
  }

  /**
   * Close the websocket
   */
  def close()
  {
    socket.close()
    if(keepaliveTimer != null) { 
      clearInterval(keepaliveTimer)
      keepaliveTimer = null
    }
    connected() = false
  }

  var nextMessageId = 0l;
  val messageCallbacks = mutable.Map[Long, Promise[js.Dynamic]]()
  def withResponse(arguments: mutable.Map[String,Any]): Promise[js.Dynamic] =
    withResponse(arguments.toSeq:_*)
  def withResponse(arguments: (String, Any)*): Promise[js.Dynamic] =
  {
    if(!connected.now){
      throw new RuntimeException("Websocket not connected")
    }
    val messageId = nextMessageId
    nextMessageId = nextMessageId + 1
    val ret = Promise[js.Dynamic]()
    messageCallbacks.put(messageId, ret)
    socket.send(JSON.stringify(
      js.Dictionary(
        (arguments :+ ("messageId" -> messageId.toInt)):_*
      )
    ))
    ret
  }

  /**
   * Allocate a module and insert or append it into the workflow
   * @param command       A [[CommandDescription]] expressing the command
   * @param beforeModule  If Some(moduleId), insert before moduleId; if None, append.
   * @return              A future for the identifier of the inserted module.  The 
   *                      future is guaranteed to resolve before the corresponding 
   *                      cell insert event.
   */
  def allocateModule(
    command: ModuleCommand,
    atPosition: Option[Int]
  ): Future[Identifier] = 
  {
    val request = 
      command.asInstanceOf[js.Dictionary[Any]] ++ (
        atPosition match { 
          case None => 
            js.Dictionary("op" -> "workflow.append")
          case Some(id) => 
            js.Dictionary("op" -> "workflow.insert", "modulePosition" -> atPosition)
        }
      )

/*
          _.identifier.asInstanceOf[Identifier] }
*/
    withResponse(request)
      .future
      .map { x => 
        println(x)
        ???
      }
  }

  def onConnected(event: dom.Event)
  {
    connected() = true
    println("Connected!")
    awaitingReSync = true
    socket.send(
      JSON.stringify(
        js.Dictionary(
          "operation" -> "subscribe",
          "projectId" -> projectId,
          "branchId" -> branchId
        )
      )
    )
  }
  def onClosed(event: dom.Event)
  {
    if(keepaliveTimer != null) { 
      clearInterval(keepaliveTimer)
      keepaliveTimer = null
    }
    connected() = false
  }
  def onError(event: dom.Event) = 
  {
    println(s"Error: $event")
  }
  def onMessage(message: dom.MessageEvent) =
  {
    // println(s"Got: ${event.data}")
    if(awaitingReSync){
      val base = JSON.parse(message.data.asInstanceOf[String])
                     .asInstanceOf[WorkflowDescription]
      println("Got initial sync")
      modules.clear()
      modules ++= base.modules
                      .map { new ModuleSubscription(_, this) }
      awaitingReSync = false
    } else {
      val event = JSON.parse(message.data.asInstanceOf[String])
      println(s"Got Event: ${event.operation}")
      event.operation.asInstanceOf[String] match {
        case "response" => 
          messageCallbacks.remove(event.messageId.asInstanceOf[Int].toLong) match {
            case Some(promise) => promise.success(event)
            case None => println(s"WARNING: Response to unsent messageId: ${event.messageId}")
          }

        case "insert_cell" => 
          modules.insert(
            event.position.asInstanceOf[Int],
            new ModuleSubscription(
              event.cell.asInstanceOf[ModuleDescription], 
              this
            )
          )
        case "update_cell" => 
          modules.update(
            event.position.asInstanceOf[Int],
            new ModuleSubscription(
              event.cell.asInstanceOf[ModuleDescription],
              this
            )
          )
        case "delete_cell" => 
          modules.remove(
            event.position.asInstanceOf[Int]
          )
        case "update_cell_state" =>
          println(s"State Update: ${event.state} @ ${event.position}")
          modules(event.position.asInstanceOf[Int]).state() = 
            ExecutionState(event.state.asInstanceOf[Int])
        case "append_cell_message" =>
          println(s"New Message")
          modules(event.position.asInstanceOf[Int])
            .messages += new StreamedMessage(
                            event.message.asInstanceOf[MessageDescription], 
                            StreamType(event.stream.asInstanceOf[Int])
                         )
        case "advance_result_id" => 
          println("Reset Result")
          val module = modules(event.position.asInstanceOf[Int])
          module.messages.clear()
          module.outputs() = Map[String,Artifact]()
        case "update_cell_outputs" => 
          val module = modules(event.position.asInstanceOf[Int])
          println(s"Adding outputs: ${event.outputs} -> ${module.outputs}")
          module.outputs() = 
            event.outputs.asInstanceOf[js.Array[ArtifactSummary]]
                         .map { artifact => 
                            println(s"Artifact: ${artifact.id}: ${artifact.category}")
                            artifact.name -> 
                              new Artifact(artifact)
                          }
                         .toMap
        case "pong" => ()
        case other => 
          println(s"Unknown operation $other\n$event")
      }
    }
  }
}