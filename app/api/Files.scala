/**
 *
 */
package api
import play.api.mvc.Controller
import play.api.mvc.Action
import play.api.libs.json.Json._
import services.Services
import play.api.Logger
import models.File
import play.api.libs.json.JsValue
import play.api.libs.iteratee.Enumerator
import models.PreviewDAO
import models.Preview
import models.Dataset
import com.mongodb.casbah.Imports._
import com.mongodb.WriteConcern
import models.FileDAO
import org.bson.types.ObjectId
import java.io.FileInputStream
import play.api.Play.current
import services.RabbitmqPlugin
import services.ExtractorMessage
import services.ElasticsearchPlugin
import play.api.libs.json.Json._
import play.api.libs.json._

/**
 * Json API for files.
 * 
 * @author Luigi Marini
 *
 */
object Files extends Controller {
  
  def get(id: String) = Authenticated { 
    Action { implicit request =>
	    Logger.info("GET file with id " + id)    
	    Services.files.getFile(id) match {
	      case Some(file) => Ok(jsonFile(file))
	      case None => {Logger.error("Error getting file" + id); InternalServerError}
	    }
    }
  }
  
  /**
   * List all files.
   */
  def list = Authenticated {
    Action {
      val list = for (f <- Services.files.listFiles()) yield jsonFile(f)
      Ok(toJson(list))
    }
  }
  
  /**
   * Download file using http://en.wikipedia.org/wiki/Chunked_transfer_encoding
   */
  def download(id: String) = Authenticated {
    Action { request =>
	    Services.files.get(id) match {
	      case Some((inputStream, filename, contentType, contentLength)) => {
	        
	         request.headers.get(RANGE) match {
	          case Some(value) => {
	            val range: (Long,Long) = value.substring("bytes=".length).split("-") match {
	              case x if x.length == 1 => (x.head.toLong, contentLength - 1)
	              case x => (x(0).toLong,x(1).toLong)
	            }
	
	            range match { case (start,end) =>
	             
	              inputStream.skip(start)
	              import play.api.mvc.{SimpleResult, ResponseHeader}
	              SimpleResult(
	                header = ResponseHeader(PARTIAL_CONTENT,
	                  Map(
	                    CONNECTION -> "keep-alive",
	                    ACCEPT_RANGES -> "bytes",
	                    CONTENT_RANGE -> "bytes %d-%d/%d".format(start,end,contentLength),
	                    CONTENT_LENGTH -> (end - start + 1).toString,
	                    CONTENT_TYPE -> contentType
	                  )
	                ),
	                body = Enumerator.fromStream(inputStream)
	              )
	            }
	          }
	          case None => {
	            Ok.stream(Enumerator.fromStream(inputStream))
	            	.withHeaders(CONTENT_TYPE -> contentType)
	            	.withHeaders(CONTENT_LENGTH -> contentLength.toString)
	            	.withHeaders(CONTENT_DISPOSITION -> ("attachment; filename=" + filename))
	          }
	        }
	      }
	      case None => {
	        Logger.error("Error getting file" + id)
	        NotFound
	      }
	    }
    }
  }
  
  /**
   * Add metadata to file.
   */
  def addMetadata(id: String) = 
    
    Action(parse.json) { request =>
      Logger.debug("Adding metadata to file " + id)
     val doc = com.mongodb.util.JSON.parse(Json.stringify(request.body)).asInstanceOf[DBObject]
     val result = FileDAO.dao.collection.update(MongoDBObject("_id" -> new ObjectId(id)), 
	              $set(Seq("metadata" -> doc)), false, false, WriteConcern.SAFE)
	 Logger.debug("Updating previews.files " + id + " with " + doc)
	 Ok(toJson("success"))
    }
  
  
  
  /**
   * Upload file using multipart form enconding.
   */
    def upload() = Action(parse.multipartFormData) { implicit request =>
      request.body.file("File").map { f =>        
        Logger.info("Uploading file " + f.filename)
        // store file
        val file = Services.files.save(new FileInputStream(f.ref.file), f.filename, f.contentType)
        file match {
          case Some(f) => {
            val key = "unknown." + f.contentType.replace("/", ".")
            // TODO RK : need figure out if we can use https
            val host = "http://" + request.host + request.path.replaceAll("upload$", "")
            val id = f.id.toString
            current.plugin[RabbitmqPlugin].foreach{_.extract(ExtractorMessage(id, host, key, Map.empty))}
            current.plugin[ElasticsearchPlugin].foreach{
              _.index("files", "file", id, List(("filename",f.filename), ("contentType", f.contentType)))
            }
            Ok(toJson(Map("id"->id)))   
          }
          case None => {
            Logger.error("Could not retrieve file that was just saved.")
            InternalServerError("Error uploading file")
          }
        }
      }.getOrElse {
         BadRequest(toJson("File not attached."))
      }
  }
    
  /**
   * Upload metadata for preview and attach it to a file.
   */  
  def uploadPreview(file_id: String) = Action(parse.multipartFormData) { implicit request =>
      request.body.file("File").map { f =>        
        Logger.info("Uploading file " + f.filename)
        // store file
        val id = PreviewDAO.save(new FileInputStream(f.ref.file), f.filename, f.contentType)
        Ok(toJson(Map("id"->id)))   
      }.getOrElse {
         BadRequest(toJson("File not attached."))
      }
  }
  
  /**
   * Add preview to file.
   */
  def attachPreview(file_id: String, preview_id: String) = Authenticated {
    Action(parse.json) { request =>
      request.body match {
        case JsObject(fields) => {
          // TODO create a service instead of calling salat directly
          FileDAO.findOneById(new ObjectId(file_id)) match { 
            case Some(file) => {
	              PreviewDAO.findOneById(new ObjectId(preview_id)) match {
	                case Some(preview) =>
	                    val metadata = fields.toMap.flatMap(tuple => MongoDBObject(tuple._1 -> tuple._2.as[String]))
	                    PreviewDAO.dao.collection.update(MongoDBObject("_id" -> new ObjectId(preview_id)), 
	                        $set(Seq("metadata"-> metadata, "file_id" -> new ObjectId(file_id))), false, false, WriteConcern.SAFE)
	                    Logger.debug("Updating previews.files " + preview_id + " with " + metadata)
	                    Ok(toJson(Map("status"->"success")))
	                case None => BadRequest(toJson("Preview not found"))
	              }
            }
	        case None => BadRequest(toJson("File not found " + file_id))
	      }
        }
        case _ => Ok("received something else: " + request.body + '\n')
    }
    }
  }
  
  def jsonFile(file: File): JsValue = {
    toJson(Map("id"->file.id.toString, "filename"->file.filename, "content-type"->file.contentType))
  }
  
  def toDBObject(fields: Seq[(String, JsValue)]): DBObject = {
      fields.map(field =>
        field match {
          // TODO handle jsarray
//          case (key, JsArray(value: Seq[JsValue])) => MongoDBObject(key -> getValueForSeq(value))
          case (key, jsObject: JsObject) => MongoDBObject(key -> toDBObject(jsObject.fields))
          case (key, jsValue: JsValue) => MongoDBObject(key -> jsValue.as[String])
        }
      ).reduce((left:DBObject, right:DBObject) => left ++ right)
    }
}