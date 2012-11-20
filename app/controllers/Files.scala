package controllers

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import models.FileMD
import play.api.Logger
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.gridfs.Imports._
import repository.DBRegistry
import java.io.FileInputStream
import java.io.PipedOutputStream
import java.io.PipedInputStream
import play.api.Play.current
import se.radley.plugin.salat._
import play.api.libs.iteratee.Iteratee
import play.api.libs.iteratee.Enumerator

/**
 * Manage files.
 * 
 * @author Luigi Marini
 */
object Files extends Controller {
  /**
   * Upload form.
   */
  val uploadForm = Form(
    mapping(
      "userid" -> nonEmptyText
    )(FileMD.apply)(FileMD.unapply)
   )
   
  /**
    * File info.
    */
  def file(id: String) = Action {
    Logger.info("GET file with id " + id)

    mongoCollection("uploads.files").findOne(MongoDBObject("_id" -> new ObjectId(id))) match {
      case Some(file) => Ok(views.html.file(file, id))
      case None => {Logger.error("Error getting file" + id); InternalServerError}
    }
  }
  
  /**
   * List files.
   */
  def list() = Action {
    Ok(views.html.filesList(mongoCollection("uploads.files").find().toList))
  }
   
  /**
   * Upload file.
   */
  def uploadFile() = Action {
    Ok(views.html.upload(uploadForm))
  }
   
  /**
   * Upload file.
   */
  def upload() = Action(parse.multipartFormData) { implicit request =>
      request.body.file("File").map { f =>        
        Logger.info("Filename " + f.filename)
        val id = DBRegistry.fileService.save(new FileInputStream(f.ref.file), f.filename)
        Redirect(routes.Files.file(id))    
      }.getOrElse {
         BadRequest("File not attached.")
      }
  }
  
  /**
   * Stream based uploading of files.
   */
  def uploadFileStreaming() = Action(parse.multipartFormData(myPartHandler)) {
      request => Ok("Done")
  }

  def myPartHandler: BodyParsers.parse.Multipart.PartHandler[MultipartFormData.FilePart[Result]] = {
        parse.Multipart.handleFilePart {
          case parse.Multipart.FileInfo(partName, filename, contentType) =>
            Logger.info("Part: " + partName + " filename: " + filename + " contentType: " + contentType);
            val files = gridFS("uploads")
            
            //Set up the PipedOutputStream here, give the input stream to a worker thread
            val pos:PipedOutputStream = new PipedOutputStream();
            val pis:PipedInputStream  = new PipedInputStream(pos);
            val worker:foo.UploadFileWorker = new foo.UploadFileWorker(pis, files);
            worker.contentType = contentType.get;
            worker.start();

//            val mongoFile = files.createFile(f.ref.file)
//            val filename = f.ref.file.getName()
//            Logger.info("Uploading file " + filename)
//            mongoFile.filename = filename
//            mongoFile.contentType = play.api.libs.MimeTypes.forFileName(filename).getOrElse(play.api.http.ContentTypes.BINARY)
//            mongoFile.save
//            val id = mongoFile.getAs[ObjectId]("_id").get.toString
//            Ok(views.html.file(mongoFile.asDBObject, id))
            
            
            //Read content to the POS
            Iteratee.fold[Array[Byte], PipedOutputStream](pos) { (os, data) =>
              os.write(data)
              os
            }.mapDone { os =>
              os.close()
              Ok("upload done")
            }
        }
   }
  
  /**
   * Download file using http://en.wikipedia.org/wiki/Chunked_transfer_encoding
   */
  def download(id: String) = Action {
    DBRegistry.fileService.get(id) match {
      case Some((inputStream, filename)) => {
    	Ok.stream(Enumerator.fromStream(inputStream))
    	  .withHeaders(CONTENT_DISPOSITION -> ("attachment; filename=" + filename))
      }
      case None => {
        Logger.error("Error getting file" + id)
        NotFound
      }
    }
  }
}