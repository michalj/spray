package spray.examples

import java.io.File
import org.parboiled.common.FileUtils
import scala.concurrent.duration._
import akka.actor.{Props, Actor}
import akka.pattern.ask
import spray.routing.{HttpService, RequestContext}
import spray.routing.directives.CachingDirectives
import spray.can.server.Stats
import spray.can.Http
import spray.httpx.marshalling.Marshaller
import spray.httpx.encoding.Gzip
import spray.util._
import spray.http._
import MediaTypes._
import CachingDirectives._


// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class DemoServiceActor extends Actor with DemoService {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  // this actor only runs our route, but you could add
  // other things here, like request stream processing,
  // timeout handling or alternative handler registration
  def receive = runRoute(demoRoute)
}


// this trait defines our service behavior independently from the service actor
trait DemoService extends HttpService {

  // we use the enclosing ActorContext's or ActorSystem's dispatcher for our Futures and Scheduler
  implicit def executionContext = actorRefFactory.dispatcher

  val demoRoute = {
    get {
      path("") {
        respondWithMediaType(`text/html`) { // XML is marshalled to `text/xml` by default, so we simply override here
          complete(index)
        }
      } ~
      path("ping") {
        complete("PONG!")
      } ~
      path("stream1") {
        respondWithMediaType(`text/html`) {
          // we detach in order to move the blocking code inside the simpleStringStream off the service actor
          detachTo(singleRequestServiceActor) {
            complete(simpleStringStream)
          }
        }
      } ~
      path("stream2") {
        sendStreamingResponse
      } ~
      path("stream-large-file") {
        encodeResponse(Gzip) {
          getFromFile(largeTempFile)
        }
      } ~
      path("stats") {
        complete {
          actorRefFactory.actorFor("/user/IO-HTTP/listener-0")
            .ask(Http.GetStats)(1.second)
            .mapTo[Stats]
        }
      } ~
      path("timeout") { ctx =>
        // we simply let the request drop to provoke a timeout
      } ~
      path("cached") {
        cache(simpleRouteCache) { ctx =>
          in(1500.millis) {
            ctx.complete("This resource is only slow the first time!\n" +
              "It was produced on " + DateTime.now.toIsoDateTimeString + "\n\n" +
              "(Note that your browser will likely enforce a cache invalidation with a\n" +
              "`Cache-Control: max-age=0` header when you click 'reload', so you might need to `curl` this\n" +
              "resource in order to be able to see the cache effect!)")
          }
        }
      } ~
      path("crash") { ctx =>
        sys.error("crash boom bang")
      } ~
      path("fail") {
        failWith(new RuntimeException("aaaahhh"))
      }
    } ~
    (post | parameter('method ! "post")) {
      path("stop") { ctx =>
        ctx.complete("Shutting down in 1 second...")
        in(1.second) { actorSystem.shutdown() }
      }
    }
  }

  lazy val simpleRouteCache = routeCache()

  lazy val index =
    <html>
      <body>
        <h1>Say hello to <i>spray-routing</i> on <i>spray-can</i>!</h1>
        <p>Defined resources:</p>
        <ul>
          <li><a href="/ping">/ping</a></li>
          <li><a href="/stream1">/stream1</a> (via a Stream[T])</li>
          <li><a href="/stream2">/stream2</a> (manually)</li>
          <li><a href="/stream-large-file">/stream-large-file</a></li>
          <li><a href="/stats">/stats</a></li>
          <li><a href="/timeout">/timeout</a></li>
          <li><a href="/cached">/cached</a></li>
          <li><a href="/crash">/crash</a></li>
          <li><a href="/fail">/fail</a></li>
          <li><a href="/stop?method=post">/stop</a></li>
        </ul>
      </body>
    </html>

  // we prepend 2048 "empty" bytes to push the browser to immediately start displaying the incoming chunks
  lazy val streamStart = " " * 2048 + "<html><body><h2>A streaming response</h2><p>(for 15 seconds)<ul>"
  lazy val streamEnd = "</ul><p>Finished.</p></body></html>"

  def simpleStringStream: Stream[String] = {
    val secondStream = Stream.continually {
      // CAUTION: we block here to delay the stream generation for you to be able to follow it in your browser,
      // this is only done for the purpose of this demo, blocking in actor code should otherwise be avoided
      Thread.sleep(500)
      "<li>" + DateTime.now.toIsoDateTimeString + "</li>"
    }
    streamStart #:: secondStream.take(15) #::: streamEnd #:: Stream.empty
  }

  // simple case class whose instances we use as send confirmation message for streaming chunks
  case class Ok(remaining: Int)

  def sendStreamingResponse(ctx: RequestContext): Unit =
    actorRefFactory.actorOf {
      Props {
        new Actor with SprayActorLogging {
          // we use the successful sending of a chunk as trigger for scheduling the next chunk
          val responseStart = HttpResponse(entity = HttpEntity(`text/html`, streamStart))
          ctx.responder ! ChunkedResponseStart(responseStart).withAck(Ok(16))

          def receive = {
            case Ok(0) =>
              ctx.responder ! MessageChunk(streamEnd)
              ctx.responder ! ChunkedMessageEnd()
              context.stop(self)

            case Ok(remaining) =>
              in(500.millis) {
                val nextChunk = MessageChunk("<li>" + DateTime.now.toIsoDateTimeString + "</li>")
                ctx.responder ! nextChunk.withAck(Ok(remaining - 1))
              }

            case ev: Http.ConnectionClosed =>
              log.warning("Stopping response streaming due to {}", ev)
          }
        }
      }
    }

  implicit val statsMarshaller: Marshaller[Stats] =
    Marshaller.delegate[Stats, String](ContentType.`text/plain`) { stats =>
      "Uptime                : " + stats.uptime.formatHMS + '\n' +
      "Total requests        : " + stats.totalRequests + '\n' +
      "Open requests         : " + stats.openRequests + '\n' +
      "Max open requests     : " + stats.maxOpenRequests + '\n' +
      "Total connections     : " + stats.totalConnections + '\n' +
      "Open connections      : " + stats.openConnections + '\n' +
      "Max open connections  : " + stats.maxOpenConnections + '\n' +
      "Requests timed out    : " + stats.requestTimeouts + '\n'
    }

  def in[U](duration: FiniteDuration)(body: => U): Unit =
    actorSystem.scheduler.scheduleOnce(duration)(body)

  lazy val largeTempFile: File = {
    val file = File.createTempFile("streamingTest", ".txt")
    FileUtils.writeAllText((1 to 1000) map ("This is line " + _) mkString "\n", file)
    file.deleteOnExit()
    file
  }
}