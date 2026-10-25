package com.lightroom.mobile

import org.junit.Assert.*
import org.junit.Test

class WorkflowTest {
    @Test fun metadataRoundTripPreservesLocationAndProfiles() {
        val value = PhotoMetadata("Portrait", "© Owner", listOf("people", "sunset"), 12.3, -45.6, "2026:10:25 18:05:00", "Canon", "EOS", "Lens", 35.0, 2.8, "maker|lens", "a.icc")
        assertEquals(value, PhotoMetadata.fromJson(value.toJson()))
    }
    @Test fun metadataNormalizesKeywordsAndSearchesAllFields() {
        val value = PhotoMetadata(caption="Beach sunset", keywords=listOf(" Travel ", "travel", "", "Sky"), cameraModel="EOS R5").validated()
        assertEquals(listOf("Travel", "Sky"), value.keywords)
        assertTrue(value.matches("SUNSET")); assertTrue(value.matches("r5")); assertTrue(value.matches("sky")); assertFalse(value.matches("absent"))
    }
    @Test fun invalidCoordinatesAndDatesAreRejected() {
        listOf(PhotoMetadata(latitude=91.0,longitude=0.0), PhotoMetadata(latitude=0.0), PhotoMetadata(latitude=Double.NaN,longitude=0.0), PhotoMetadata(capturedAt="2026:02:30 12:00:00")).forEach {
            assertTrue(runCatching { it.validated() }.isFailure)
        }
    }
    @Test fun batchPreservesGeometryAndLocalEditsByDefault() {
        val target=DevelopState(rotation=90,cropLeft=.2f,masks=listOf(LocalMask("own")),lensBlur=20f)
        val source=DevelopState(exposure=2f,contrast=30f,rotation=180,masks=listOf(LocalMask("other")),lensBlur=80f,defringe=35f)
        val result=batchRecipe(target,source,false)
        assertEquals(2f,result.exposure); assertEquals(30f,result.contrast); assertEquals(90,result.rotation); assertEquals(.2f,result.cropLeft)
        assertEquals(target.masks,result.masks); assertEquals(20f,result.lensBlur)
        assertEquals(35f,result.defringe)
        assertEquals(source.masks,batchRecipe(target,source,true).masks)
    }
    @Test fun batchCopiesDefaultProfileAndEmptyCurves() {
        val result=batchRecipe(DevelopState(profile="Monochrome",curves=mapOf("RGB" to listOf(CurvePoint(0f,.2f)))),DevelopState(),false)
        assertEquals("Adobe Color",result.profile); assertTrue(result.curves.isEmpty())
    }
    @Test fun editDescriptionNamesChangedControls() {
        assertTrue(editDescription(DevelopState(),DevelopState(exposure=1f)).contains("Exposure"))
    }
    @Test fun calibratedDistortionModelsAndInterpolation() {
        val a=LensCalibration("poly3",20.0,0.0,mapOf("k1" to .1))
        val b=a.copy(focal=40.0,coefficients=mapOf("k1" to .3))
        assertEquals(1.0,a.distortion(1.0),1e-9)
        assertEquals(.9,a.distortion(0.0),1e-9)
        val lens=LensProfile("Maker","Lens",1.0,listOf(a,b),emptyList(),emptyList())
        assertEquals(.2,lens.at(lens.distortion,30.0)!!.value("k1"),1e-9)
        assertEquals(.1,lens.at(lens.distortion,10.0)!!.value("k1"),1e-9)
    }
    @Test fun tcaUsesNeutralDefaults() {
        assertEquals(1.0,LensCalibration("linear",35.0,0.0,emptyMap()).tca(.5,true),0.0)
        assertEquals(1.0,LensCalibration("poly3",35.0,0.0,emptyMap()).tca(.5,false),0.0)
    }
    @Test fun xmpEscapesTextAndExcludesGpsByDefault() {
        val value=PhotoMetadata(caption="A&B <tag>",keywords=listOf("red & blue"),latitude=12.3,longitude=45.6)
        val xml=PhotoMetadataIO.xmp(value,false)
        assertTrue(xml.contains("A&amp;B &lt;tag&gt;")); assertFalse(xml.contains("GPSLatitude"))
        assertTrue(PhotoMetadataIO.xmp(value,true).contains("GPSLatitude"))
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }
}
