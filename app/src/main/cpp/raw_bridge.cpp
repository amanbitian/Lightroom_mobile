#include <jni.h>
#include <android/bitmap.h>
#include <libraw/libraw.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <string>
#include <lcms2.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lightroom_mobile_NativeRaw_validateProfile(JNIEnv *env, jobject, jstring path) {
    const char *name=env->GetStringUTFChars(path,nullptr);
    auto profile=cmsOpenProfileFromFile(name,"r"); env->ReleaseStringUTFChars(path,name);
    if(!profile)return false;
    auto output=cmsCreate_sRGBProfile();
    auto transform=cmsCreateTransform(profile,TYPE_RGBA_16,output,TYPE_RGBA_16,INTENT_PERCEPTUAL,0);
    bool valid=cmsGetColorSpace(profile)==cmsSigRgbData && cmsGetDeviceClass(profile)==cmsSigInputClass && transform;
    if(transform)cmsDeleteTransform(transform);cmsCloseProfile(output);cmsCloseProfile(profile);return valid;
}

// LibRaw performs sensor unpacking, black subtraction, WB, camera-matrix conversion, demosaic
// (including X-Trans) and highlight reconstruction. A failed RAW never becomes a JPEG silently.
extern "C" JNIEXPORT jobject JNICALL
Java_com_lightroom_mobile_NativeRaw_decodeFile(JNIEnv *env, jobject, jstring path, jint maxSide, jstring icc, jboolean precision) {
    try {
    const char *filename = env->GetStringUTFChars(path, nullptr);
    LibRaw raw;
    raw.imgdata.params.use_camera_wb = 1;
    raw.imgdata.params.use_camera_matrix = 1;
    int rc = raw.open_file(filename);
    env->ReleaseStringUTFChars(path, filename);
    if (rc != LIBRAW_SUCCESS) return nullptr;
    raw.imgdata.params.output_color = 1; // sRGB
    raw.imgdata.params.output_bps = 16;
    raw.imgdata.params.user_qual = 3; // AHD; LibRaw selects its X-Trans implementation as needed
    raw.imgdata.params.highlight = 3; // Reconstruct clipped highlights.
    raw.imgdata.params.no_auto_bright = 1;
    std::string profile;
    if (icc) {
        const char *p = env->GetStringUTFChars(icc, nullptr); profile = p; env->ReleaseStringUTFChars(icc, p);
        auto handle = cmsOpenProfileFromFile(profile.c_str(), "r");
        if (!handle) return nullptr;
        bool rgb = cmsGetColorSpace(handle) == cmsSigRgbData;
        cmsCloseProfile(handle);
        if (!rgb) return nullptr;
        raw.imgdata.params.camera_profile = const_cast<char *>(profile.c_str());
    }
    // Limit native allocation before unpacking pathological/huge inputs.
    raw.imgdata.rawparams.max_raw_memory_mb = 384;
    if (uint64_t(raw.imgdata.sizes.raw_width) * raw.imgdata.sizes.raw_height > 100000000ULL) return nullptr;
    if (raw.unpack() != LIBRAW_SUCCESS || raw.dcraw_process() != LIBRAW_SUCCESS) return nullptr;
    int error = 0;
    std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)> image(raw.dcraw_make_mem_image(&error), &LibRaw::dcraw_clear_mem);
    if (!image || error || image->type != LIBRAW_IMAGE_BITMAP || image->colors != 3 || image->bits != 16) return nullptr;
    float scale = std::min(1.f, float(std::max(1, maxSide)) / std::max(image->width, image->height));
    int w = std::max(1, int(image->width * scale)), h = std::max(1, int(image->height * scale));
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jobject config = env->GetStaticObjectField(configClass, env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;"));
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"), w, h, config);
    if (!bitmap || env->ExceptionCheck()) return nullptr;
    jobject buffer = nullptr;
    uint16_t *out16 = nullptr;
    if (precision) {
        jclass bufferClass = env->FindClass("java/nio/ByteBuffer");
        buffer = env->CallStaticObjectMethod(bufferClass, env->GetStaticMethodID(bufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;"), w*h*6);
        if (!buffer || env->ExceptionCheck()) return nullptr;
        out16 = reinterpret_cast<uint16_t *>(env->GetDirectBufferAddress(buffer));
    }
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) || AndroidBitmap_lockPixels(env, bitmap, &pixels)) return nullptr;
    auto *src = reinterpret_cast<const uint16_t *>(image->data);
    for (int y = 0; y < h; ++y) {
        auto *row = reinterpret_cast<uint8_t *>(pixels) + y * info.stride;
        float sy = (y + .5f) * image->height / h - .5f;
        int y0 = std::clamp(int(sy), 0, int(image->height)-1), y1 = std::min(y0+1, int(image->height)-1);
        float fy = std::clamp(sy-y0, 0.f, 1.f);
        for (int x = 0; x < w; ++x) {
            float sx = (x + .5f) * image->width / w - .5f;
            int x0 = std::clamp(int(sx), 0, int(image->width)-1), x1 = std::min(x0+1, int(image->width)-1);
            float fx = std::clamp(sx-x0, 0.f, 1.f);
            for (int c = 0; c < 3; ++c) {
                auto at = [&](int ix, int iy) { return float(src[(iy*image->width+ix)*3+c]); };
                float value = (at(x0,y0)*(1-fx)+at(x1,y0)*fx)*(1-fy)+(at(x0,y1)*(1-fx)+at(x1,y1)*fx)*fy;
                row[x*4+c] = uint8_t(std::clamp(int(value/257.f+.5f),0,255));
                if (out16) out16[(y*w+x)*3+c] = uint16_t(std::clamp(int(value+.5f),0,65535));
            }
            row[x*4+3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    jclass frameClass = env->FindClass("com/lightroom/mobile/RawFrame");
    return env->NewObject(frameClass, env->GetMethodID(frameClass, "<init>", "(Landroid/graphics/Bitmap;Ljava/nio/ByteBuffer;)V"), bitmap, buffer);
    } catch (...) { return nullptr; }
}
