package com.lightroom.mobile

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class WorkflowDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun store(): LibraryStore = LibraryStore(context,"workflow-${java.util.UUID.randomUUID()}.db","workflow-${java.util.UUID.randomUUID()}")
    @Test fun historySurvivesReopenDeduplicatesAndRestoresOriginal() {
        val id=java.util.UUID.randomUUID().toString(); val db="workflow-$id.db"; val prefs="workflow-$id"
        val photo=PhotoItem("file:///history.jpg","history.jpg")
        LibraryStore(context,db,prefs).apply {
            savePhotos(listOf(photo)); saveEdit(photo.uri,DevelopState(exposure=1f)); saveEdit(photo.uri,DevelopState(exposure=1f))
            assertEquals(2,history(photo.uri).size); snapshot(photo.uri,"Portrait",DevelopState(exposure=1f)); close()
        }
        LibraryStore(context,db,prefs).apply {
            assertEquals(3,history(photo.uri).size); assertEquals(1f,loadEdit(photo.uri).exposure)
            deleteEdit(photo.uri); assertFalse(hasEdit(photo.uri)); assertEquals(4,history(photo.uri).size)
            assertTrue(history(photo.uri).any { it.named && it.label=="Portrait" })
            removePhoto(photo.uri); assertTrue(history(photo.uri).isEmpty()); close()
        }
    }
    @Test fun batchIsRecoverableAndPreservesIndividualCrop() {
        store().apply {
            val a=PhotoItem("file:///a.jpg","a.jpg"); val b=PhotoItem("file:///b.jpg","b.jpg")
            savePhotos(listOf(a,b)); saveEdit(b.uri,DevelopState(rotation=90,cropLeft=.1f))
            applyBatch(listOf(a.uri,b.uri),DevelopState(exposure=2f),false)
            assertEquals(2f,loadEdit(a.uri).exposure); assertEquals(90,loadEdit(b.uri).rotation); assertEquals(.1f,loadEdit(b.uri).cropLeft)
            val before=history(b.uri).first{it.named}.state; saveEdit(b.uri,before); assertEquals(0f,loadEdit(b.uri).exposure); close()
        }
    }
    @Test fun queueSnapshotsRecipesAndMetadataAndCancels() {
        store().apply {
            val a=PhotoItem("file:///queue.jpg","queue.jpg"); savePhotos(listOf(a)); saveEdit(a.uri,DevelopState(exposure=1f)); saveMetadata(a.uri,PhotoMetadata(caption="Before"))
            enqueueExports(listOf(a),ExportOptions(includeLocation=false)); saveEdit(a.uri,DevelopState(exposure=3f)); saveMetadata(a.uri,PhotoMetadata(caption="After"))
            val job=nextExport()!!; assertEquals(1f,job.state.exposure); assertEquals("Before",job.metadata.caption); assertFalse(job.options.includeLocation)
            cancelExports(); assertNull(nextExport()); close()
        }
    }
    @Test fun migrationFromV2PreservesCatalogAndRecipe() {
        val name="migration-${java.util.UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name,0,null).use { db ->
            db.execSQL("CREATE TABLE photos(uri TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,rating INTEGER,flagged INTEGER,rejected INTEGER,imported_at INTEGER)")
            db.execSQL("CREATE TABLE edits(uri TEXT PRIMARY KEY NOT NULL,recipe TEXT NOT NULL,updated_at INTEGER)")
            db.execSQL("INSERT INTO photos VALUES('photo','Old',4,0,0,1)")
            db.execSQL("INSERT INTO edits VALUES('photo',?,1)",arrayOf(DevelopState(exposure=1f).toJson().toString())); db.version=2
        }
        LibraryStore(context,name,"prefs-$name").apply { assertEquals(4,loadPhotos().single().rating); assertEquals(1f,loadEdit("photo").exposure); saveEdit("photo",DevelopState(exposure=2f)); assertEquals(2,history("photo").size); close() }
    }
    @Test fun lensDatabaseLoadsAndAmbiguousMatchDoesNotGuess() {
        val profiles=LensProfiles.load(context); assertTrue(profiles.size>500)
        assertTrue(profiles.any{it.distortion.isNotEmpty()}); assertNull(LensProfiles.match(profiles,PhotoMetadata(lens="Not a known lens")))
    }
    @Test fun versionsCaptureMetadataAndProfileChanges() {
        store().apply {
            val photo=PhotoItem("file:///profiles.dng","profiles.dng");savePhotos(listOf(photo))
            saveMetadata(photo.uri,PhotoMetadata(caption="Before",lensProfile="one"))
            val version=snapshot(photo.uri,"First",DevelopState(exposure=1f))
            saveMetadata(photo.uri,PhotoMetadata(caption="After",lensProfile="two"))
            val old=history(photo.uri).first{it.id==version};assertEquals("one",old.metadata!!.lensProfile);assertEquals("Before",old.metadata.caption)
            saveMetadata(photo.uri,old.metadata);assertEquals("Before",loadMetadata(photo.uri)!!.caption);close()
        }
    }
    @Test fun jpegMetadataAndGpsExportSwitches() = runBlocking {
        LiteRtModels.init(context)
        val source=File(context.cacheDir,"metadata-input.png")
        Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff8899aa.toInt())}.let{ b->source.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}; b.recycle() }
        val photo=PhotoItem(Uri.fromFile(source).toString(),source.name)
        val m=PhotoMetadata(caption="Test caption",copyright="Owner",keywords=listOf("sky"),latitude=12.3,longitude=45.6)
        for (location in listOf(false,true)) {
            val result=ImageExporter.export(context,photo,DevelopState(),ExportOptions(includeLocation=location),m)
            assertTrue(result is ImageExporter.ExportResult.Saved); val uri=(result as ImageExporter.ExportResult.Saved).uri
            context.contentResolver.openInputStream(uri)!!.use { val e=ExifInterface(it); assertEquals("Test caption",e.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)); assertEquals(location,e.latLong!=null) }
            context.contentResolver.delete(uri,null,null)
        }
    }
    @Test fun rawUsesSensorDataWithoutEmbeddedJpegAndRejectsCorruptFile() {
        assertTrue("Native RAW library must load",NativeRaw.available)
        val file=File(context.cacheDir,"sensor-fixture.dng"); file.outputStream().use{it.write(sensorDng())}
        val decoded=RawDecoder.decode(context,Uri.fromFile(file),128)
        assertNotNull("DNG has only CFA sensor samples; no JPEG fallback exists",decoded)
        assertEquals(64,decoded!!.width); assertEquals(48,decoded.height)
        assertTrue(decoded.getPixel(48,24)!=decoded.getPixel(16,24)); decoded.recycle()
        val bad=File(context.cacheDir,"bad-fixture.dng"); bad.outputStream().use{it.write(byteArrayOf(1,2,3))}
        assertNull(RawDecoder.decode(context,Uri.fromFile(bad),128))
    }
    @Test fun rawRetainsMoreThanEightBitPrecisionThroughExport() = runBlocking {
        LiteRtModels.init(context)
        val file=File(context.cacheDir,"precision-fixture.dng"); file.outputStream().use{it.write(sensorDng())}
        val raw=NativeRaw.decodeFrame(context,Uri.fromFile(file),128)!!
        val samples=raw.rgb16!!.order(ByteOrder.nativeOrder()).asShortBuffer()
        assertTrue((0 until samples.capacity()).any { (samples.get(it).toInt() and 65535)%257!=0 })
        raw.bitmap.recycle()
        val result=ImageExporter.export(context,PhotoItem(Uri.fromFile(file).toString(),file.name),DevelopState(),ExportOptions(format="TIFF",includeMetadata=false))
        assertTrue(result is ImageExporter.ExportResult.Saved)
        val uri=(result as ImageExporter.ExportResult.Saved).uri
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val data=input.readBytes(); val buffer=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); val ifd=buffer.getInt(4)
            val count=buffer.getShort(ifd).toInt(); val entry=(0 until count).first{buffer.getShort(ifd+2+it*12).toInt()==273}
            val offset=buffer.getInt(ifd+2+entry*12+8)
            assertTrue((offset until data.size-1 step 2).any{(buffer.getShort(it).toInt() and 65535)%257!=0})
        }
        context.contentResolver.delete(uri,null,null)
        Unit
    }
    @Test fun pngAndTiffEmbedXmpAndRespectMetadataOff() = runBlocking {
        LiteRtModels.init(context)
        val file=File(context.cacheDir,"formats-fixture.png")
        Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff778899.toInt())}.let{b->file.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        val photo=PhotoItem(Uri.fromFile(file).toString(),file.name)
        for(format in listOf("PNG","PNG16","TIFF"))for(include in listOf(true,false)) {
            val result=ImageExporter.export(context,photo,DevelopState(),ExportOptions(format=format,includeMetadata=include),PhotoMetadata(caption="Export metadata fixture",latitude=10.0,longitude=20.0))
            assertTrue(result is ImageExporter.ExportResult.Saved); val uri=(result as ImageExporter.ExportResult.Saved).uri
            val bytes=context.contentResolver.openInputStream(uri)!!.use{it.readBytes()};val text=String(bytes,Charsets.ISO_8859_1)
            assertEquals(include,text.contains("Export metadata fixture"));assertFalse(text.contains("GPSLatitude"))
            context.contentResolver.delete(uri,null,null)
        }
    }
    @Test fun lensCorrectionPreservesSixteenBitAndIdentityMapping() {
        val bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff808080.toInt())}
        val samples=ByteBuffer.allocateDirect(32*24*6).order(ByteOrder.nativeOrder());repeat(32*24*3){samples.putShort((32768+it%31).toShort())}
        val lens=LensProfile("Test","Identity",1.0,listOf(LensCalibration("poly3",35.0,0.0,mapOf("k1" to 0.0))),emptyList(),emptyList())
        val corrected=LensProfiles.correctFrame(bitmap,lens,PhotoMetadata(focalLength=35.0),samples)
        val output=corrected.rgb16!!.order(ByteOrder.nativeOrder()).asShortBuffer()
        assertEquals(32768,output.get(0).toInt() and 65535);assertEquals(32769,output.get(1).toInt() and 65535); corrected.bitmap.recycle()
    }
    @Test fun backgroundQueueExportsAndReportsIndividualFailures() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { context.startActivity(android.content.Intent(context,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        instrumentation.waitForIdleSync()
        val id=java.util.UUID.randomUUID().toString()
        val file=File(context.cacheDir,"service-$id.png")
        Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff8899aa.toInt())}.let{b->file.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        val good=PhotoItem(Uri.fromFile(file).toString(),file.name);val bad=PhotoItem("file:///unavailable-$id.png","unavailable.png")
        val catalog=LibraryStore(context)
        val jobs=catalog.enqueueExports(listOf(good,bad),ExportOptions(includeMetadata=false))
        context.startForegroundService(android.content.Intent(context,BatchExportService::class.java))
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        val deadline=android.os.SystemClock.elapsedRealtime()+15000
        while(android.os.SystemClock.elapsedRealtime()<deadline && jobs.any{catalog.exportStatus(it)=="queued"}) Thread.sleep(100)
        val saved=catalog.exportStatus(jobs[0]).orEmpty();assertTrue(saved.startsWith("saved:"))
        assertTrue(catalog.exportStatus(jobs[1]).orEmpty().startsWith("failed:"))
        context.contentResolver.delete(Uri.parse(saved.removePrefix("saved:")),null,null)
        jobs.forEach{catalog.removeExportJob(it)};catalog.close()
    }
    @Test fun xTransDngUsesNativeDemosaic() {
        val file=File(context.cacheDir,"xtrans-fixture.dng");file.outputStream().use{it.write(sensorDng(true))}
        val decoded=NativeRaw.decode(context,Uri.fromFile(file),128)
        assertNotNull(decoded);assertEquals(128,decoded!!.width);decoded.recycle()
    }
    @Test fun cameraIccIsValidatedAndAppliedByNativeEngine() {
        val directory=File(context.filesDir,"camera-profiles").apply{mkdirs()}
        val name="${"b".repeat(64)}.icc";val profile=File(directory,name)
        profile.outputStream().use{it.write(inputIcc())}
        assertTrue(NativeRaw.validateProfile(profile.path))
        val file=File(context.cacheDir,"profile-fixture.dng");file.outputStream().use{it.write(sensorDng())}
        val decoded=NativeRaw.decode(context,Uri.fromFile(file),128,name);assertNotNull(decoded);decoded!!.recycle()
        val bad=File(context.cacheDir,"bad-profile.icc");bad.outputStream().use{it.write(byteArrayOf(0,1,2))};assertFalse(NativeRaw.validateProfile(bad.path))
        profile.delete()
    }
    private fun inputIcc(): ByteArray {
        val data=ByteBuffer.allocate(344).order(ByteOrder.BIG_ENDIAN)
        fun signature(offset:Int,text:String){text.toByteArray(Charsets.US_ASCII).copyInto(data.array(),offset)}
        fun fixed(offset:Int,value:Double){data.putInt(offset,(value*65536).toInt())}
        data.putInt(0,344);data.putInt(8,0x02100000);signature(12,"scnr");signature(16,"RGB ");signature(20,"XYZ ");signature(36,"acsp")
        fixed(68,.9642);fixed(72,1.0);fixed(76,.8249);data.putInt(128,7)
        val tags=listOf("rXYZ","gXYZ","bXYZ","wtpt","rTRC","gTRC","bTRC");var offset=216
        val xyz=listOf(listOf(.4361,.2225,.0139),listOf(.3851,.7169,.0971),listOf(.1431,.0606,.7141),listOf(.9642,1.0,.8249))
        tags.forEachIndexed{i,tag->signature(132+i*12,tag);data.putInt(136+i*12,offset);data.putInt(140+i*12,if(i<4)20 else 16)
            if(i<4){signature(offset,"XYZ ");xyz[i].forEachIndexed{j,v->fixed(offset+8+j*4,v)};offset+=20}
            else{signature(offset,"curv");data.putInt(offset+8,1);data.putShort(offset+12,256);offset+=16}}
        return data.array()
    }
    private fun sensorDng(xtrans:Boolean=false): ByteArray {
        val width=if(xtrans)600 else 64;val height=if(xtrans)600 else 48
        data class Tag(val id:Int,val type:Int,val count:Int,val value:ByteArray)
        fun shorts(vararg values:Int)=ByteBuffer.allocate(values.size*2).order(ByteOrder.LITTLE_ENDIAN).apply{values.forEach{putShort(it.toShort())}}.array()
        fun longs(vararg values:Int)=ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN).apply{values.forEach{putInt(it)}}.array()
        fun rational(vararg values:Int)=longs(*values.flatMap{listOf(it,1)}.toIntArray())
        val tags=mutableListOf(Tag(256,4,1,longs(width)),Tag(257,4,1,longs(height)),Tag(258,3,1,shorts(16)),Tag(259,3,1,shorts(1)),Tag(262,3,1,shorts(32803)),
            Tag(271,2,5,"Test\u0000".toByteArray()),Tag(272,2,8,"Fixture\u0000".toByteArray()),Tag(273,4,1,longs(0)),Tag(274,3,1,shorts(1)),Tag(277,3,1,shorts(1)),Tag(278,4,1,longs(height)),Tag(279,4,1,longs(width*height*2)),
            Tag(33421,3,2,if(xtrans)shorts(6,6)else shorts(2,2)),Tag(33422,1,if(xtrans)36 else 4,if(xtrans)byteArrayOf(1,2,1,1,0,1,0,1,0,2,1,2,1,2,1,1,0,1,1,0,1,1,2,1,2,1,2,0,1,0,1,0,1,1,2,1)else byteArrayOf(0,1,1,2)),Tag(50706,1,4,byteArrayOf(1,4,0,0)),Tag(50708,2,8,"Fixture\u0000".toByteArray()),
            Tag(50714,4,1,longs(64)),Tag(50717,4,1,longs(4095)),Tag(50721,10,9,rational(1,0,0,0,1,0,0,0,1)),Tag(50728,5,3,rational(1,1,1)),Tag(50778,3,1,shorts(21)))
        tags.sortBy{it.id}; var offset=8+2+tags.size*12+4; val offsets=tags.associate{tag->tag.id to if(tag.value.size>4) offset.also{offset+=tag.value.size+(tag.value.size%2)} else -1}
        val pixelOffset=offset; val out=ByteBuffer.allocate(pixelOffset+width*height*2).order(ByteOrder.LITTLE_ENDIAN)
        out.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(tags.size.toShort())
        tags.forEach{tag->out.putShort(tag.id.toShort()).putShort(tag.type.toShort()).putInt(tag.count)
            if(tag.id==273)out.putInt(pixelOffset) else if(tag.value.size>4)out.putInt(offsets.getValue(tag.id)) else {out.put(tag.value); repeat(4-tag.value.size){out.put(0)}} }
        out.putInt(0); tags.filter{it.value.size>4}.forEach{tag->out.position(offsets.getValue(tag.id));out.put(tag.value)}
        out.position(pixelOffset); repeat(height){repeat(width){x->out.putShort((256+x*3200/width).toShort())}}; return out.array()
    }
}
