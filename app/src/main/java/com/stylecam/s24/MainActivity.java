package com.stylecam.s24;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
  private WebView web;
  private Uri lastSaved;
  private PermissionRequest pending;
  private final String HOST="stylecam.local";
  private boolean gone=false;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(Color.BLACK);
    getWindow().setNavigationBarColor(Color.BLACK);
    getWindow().getDecorView().setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
      View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    if (Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[]{Manifest.permission.CAMERA},75);
    init();
  }

  @Override public void onRequestPermissionsResult(int code,String[] names,int[] grants) {
    super.onRequestPermissionsResult(code,names,grants);
    if(code!=75) return;
    if(pending!=null) {
      PermissionRequest request=pending;
      pending=null;
      if(grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED) request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
      else request.deny();
    }
    if(web!=null) web.reload();
  }

  private void init() {
    web=new WebView(this);
    WebSettings s=web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    web.setBackgroundColor(Color.BLACK);
    web.setWebViewClient(new WebViewClient(){
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
        Uri uri=r.getUrl();
        if(!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())) return null;
        String path=uri.getPath();
        if(path==null) return null;
        if(path.equals("/")) path="/index.html";
        if(path.contains("..")) return null;
        String asset=path.substring(1);
        String type=asset.endsWith(".js")?"application/javascript":asset.endsWith(".css")?"text/css":"text/html";
        try {
          InputStream in=getAssets().open(asset);
          return new WebResourceResponse(type,"UTF-8",in);
        } catch(Exception e) {
          return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream("Not Found".getBytes()));
        }
      }
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        return !HOST.equals(r.getUrl().getHost());
      }
    });
    web.setWebChromeClient(new WebChromeClient(){
      @Override public void onPermissionRequest(PermissionRequest r) {
        runOnUiThread(()->{
          if(!HOST.equals(r.getOrigin().getHost())) {r.deny();return;}
          for(String res:r.getResources()) if(!PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)){r.deny();return;}
          if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) {
            r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
          } else {
            pending=r;
            requestPermissions(new String[]{Manifest.permission.CAMERA},75);
          }
        });
      }
      @Override public void onPermissionRequestCanceled(PermissionRequest r){if(r==pending) pending=null;}
    });
    web.addJavascriptInterface(new Gallery(),"Native");
    setContentView(web);
    web.loadUrl("https://"+HOST+"/index.html");
  }

  private final class Gallery {
    @JavascriptInterface public void savePhoto(String data) {
      if(data==null || !data.startsWith("data:image/jpeg;base64,") || data.length()>30000000) return;
      new Thread(()->{
        Uri uri=null;
        try {
          byte[] bytes=Base64.decode(data.substring(data.indexOf(',')+1),Base64.DEFAULT);
          ContentValues cv=new ContentValues();
          cv.put(MediaStore.MediaColumns.DISPLAY_NAME,"StyleCam_"+System.currentTimeMillis()+".jpg");
          cv.put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg");
          cv.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/StyleCam");
          cv.put(MediaStore.MediaColumns.IS_PENDING,1);
          uri=getContentResolver().insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),cv);
          if(uri==null)throw new IllegalStateException("Gallery unavailable");
          try(OutputStream out=getContentResolver().openOutputStream(uri)) {
            if(out==null) throw new IllegalStateException("Cannot open photo");
            out.write(bytes);
          }
          cv.clear();cv.put(MediaStore.MediaColumns.IS_PENDING,0);
          getContentResolver().update(uri,cv,null,null);
          lastSaved=uri;
          result(true,"Saved to Pictures / StyleCam");
        }catch(Exception e){
          if(uri!=null)getContentResolver().delete(uri,null,null);
          result(false,"Photo save failed");
        }
      }).start();
    }
    @JavascriptInterface public void openGallery() {
      runOnUiThread(()->{
        try {
          Intent i=new Intent(Intent.ACTION_VIEW);
          if(lastSaved!=null) {
            i.setDataAndType(lastSaved,"image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          } else i.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,"image/*");
          startActivity(i);
        }catch(Exception e){Toast.makeText(MainActivity.this,"Open your Photos app",Toast.LENGTH_SHORT).show();}
      });
    }
  }
  private void result(boolean ok,String message){
    runOnUiThread(()->{if(!gone&&web!=null)web.evaluateJavascript("window.onSaved("+ok+","+org.json.JSONObject.quote(message)+")",null);});
  }
  @Override protected void onDestroy(){
    gone=true;
    if(pending!=null)pending.deny();
    if(web!=null){web.loadUrl("about:blank");web.destroy();web=null;}
    super.onDestroy();
  }
}
