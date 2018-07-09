/*
 *  Renderer.java
 *  ARToolKit5
 *
 *  Disclaimer: IMPORTANT:  This Daqri software is supplied to you by Daqri
 *  LLC ("Daqri") in consideration of your agreement to the following
 *  terms, and your use, installation, modification or redistribution of
 *  this Daqri software constitutes acceptance of these terms.  If you do
 *  not agree with these terms, please do not use, install, modify or
 *  redistribute this Daqri software.
 *
 *  In consideration of your agreement to abide by the following terms, and
 *  subject to these terms, Daqri grants you a personal, non-exclusive
 *  license, under Daqri's copyrights in this original Daqri software (the
 *  "Daqri Software"), to use, reproduce, modify and redistribute the Daqri
 *  Software, with or without modifications, in source and/or binary forms;
 *  provided that if you redistribute the Daqri Software in its entirety and
 *  without modifications, you must retain this notice and the following
 *  text and disclaimers in all such redistributions of the Daqri Software.
 *  Neither the name, trademarks, service marks or logos of Daqri LLC may
 *  be used to endorse or promote products derived from the Daqri Software
 *  without specific prior written permission from Daqri.  Except as
 *  expressly stated in this notice, no other rights or licenses, express or
 *  implied, are granted by Daqri herein, including but not limited to any
 *  patent rights that may be infringed by your derivative works or by other
 *  works in which the Daqri Software may be incorporated.
 *
 *  The Daqri Software is provided by Daqri on an "AS IS" basis.  DAQRI
 *  MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION
 *  THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS
 *  FOR A PARTICULAR PURPOSE, REGARDING THE DAQRI SOFTWARE OR ITS USE AND
 *  OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS.
 *
 *  IN NO EVENT SHALL DAQRI BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL
 *  OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 *  SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 *  INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION,
 *  MODIFICATION AND/OR DISTRIBUTION OF THE DAQRI SOFTWARE, HOWEVER CAUSED
 *  AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE),
 *  STRICT LIABILITY OR OTHERWISE, EVEN IF DAQRI HAS BEEN ADVISED OF THE
 *  POSSIBILITY OF SUCH DAMAGE.
 *
 *  Copyright 2015 Daqri, LLC.
 *  Copyright 2011-2015 ARToolworks, Inc.
 *
 *  Author(s): Julian Looser, Philip Lamb
 *
 */

package org.artoolkit.ar.samples.ARMovie;


import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import com.alphamovie.lib.GLTextureView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

public class Renderer implements GLSurfaceView.Renderer,GLTextureView.Renderer,SurfaceTexture.OnFrameAvailableListener {
 
	private MovieController mMovieController = null;
	private static String TAG = "Renderer";

	private static final int COLOR_MAX_VALUE = 255;

	private static final int FLOAT_SIZE_BYTES = 4;
	private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
	private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
	private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
	private final float[] triangleVerticesData = {
			// X, Y, Z, U, V
			-1.0f, -1.0f, 0, 0.f, 0.f,
			1.0f, -1.0f, 0, 1.f, 0.f,
			-1.0f,  1.0f, 0, 0.f, 1.f,
			1.0f,  1.0f, 0, 1.f, 1.f,
	};

	private FloatBuffer triangleVertices;

	private final String vertexShader =
			"uniform mat4 uMVPMatrix;\n" +
					"uniform mat4 uSTMatrix;\n" +
					"attribute vec4 aPosition;\n" +
					"attribute vec4 aTextureCoord;\n" +
					"varying vec2 vTextureCoord;\n" +
					"void main() {\n" +
					"  gl_Position = uMVPMatrix * aPosition;\n" +
					"  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
					"}\n";

	private final String alphaShader = "#extension GL_OES_EGL_image_external : require\n"
			+ "precision mediump float;\n"
			+ "varying vec2 vTextureCoord;\n"
			+ "uniform samplerExternalOES sTexture;\n"
			+ "varying mediump float text_alpha_out;\n"
			+ "void main() {\n"
			+ "  vec4 color = texture2D(sTexture, vTextureCoord);\n"
			+ "  float red = %f;\n"
			+ "  float green = %f;\n"
			+ "  float blue = %f;\n"
			+ "  float accuracy = %f;\n"
			+ "  if (abs(color.r - red) <= accuracy && abs(color.g - green) <= accuracy && abs(color.b - blue) <= accuracy) {\n"
			+ "      gl_FragColor = vec4(color.r, color.g, color.b, 0.0);\n"
			+ "  } else {\n"
			+ "      gl_FragColor = vec4(color.r, color.g, color.b, 1.0);\n"
			+ "  }\n"
			+ "}\n";

	private double accuracy = 0.95;

	private String shader = alphaShader;

	private float[] mVPMatrix = new float[16];
	private float[] sTMatrix = new float[16];

	private int program;
	private int textureID;
	private int uMVPMatrixHandle;
	private int uSTMatrixHandle;
	private int aPositionHandle;
	private int aTextureHandle;

	private SurfaceTexture surface;
	private boolean updateSurface = false;

	private static int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
	private OnSurfacePrepareListener onSurfacePrepareListener;

	Renderer() {
		triangleVertices = ByteBuffer.allocateDirect(
				triangleVerticesData.length * FLOAT_SIZE_BYTES)
				.order(ByteOrder.nativeOrder()).asFloatBuffer();
		triangleVertices.put(triangleVerticesData).position(0);

		Matrix.setIdentityM(sTMatrix, 0);
	}


	private boolean isCustom;

	private float redParam = 0.0f;
	private float greenParam = 1.0f;
	private float blueParam = 0.0f;

	// Accessors.
	
    public MovieController getMovieController() {
		return mMovieController;
	}

	public void setMovieController(MovieController mc) {
		mMovieController = mc;
	}

	private int createProgram(String vertexSource, String fragmentSource) {
		int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
		if (vertexShader == 0) {
			return 0;
		}
		int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
		if (pixelShader == 0) {
			return 0;
		}

		int program = GLES20.glCreateProgram();
		if (program != 0) {
			GLES20.glAttachShader(program, vertexShader);
			checkGlError("glAttachShader");
			GLES20.glAttachShader(program, pixelShader);
			checkGlError("glAttachShader");
			GLES20.glLinkProgram(program);
			int[] linkStatus = new int[1];
			GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
			if (linkStatus[0] != GLES20.GL_TRUE) {
				Log.e(TAG, "Could not link program: ");
				Log.e(TAG, GLES20.glGetProgramInfoLog(program));
				GLES20.glDeleteProgram(program);
				program = 0;
			}
		}
		return program;
	}

	private int loadShader(int shaderType, String source) {
		int shader = GLES20.glCreateShader(shaderType);
		if (shader != 0) {
			GLES20.glShaderSource(shader, source);
			GLES20.glCompileShader(shader);
			int[] compiled = new int[1];
			GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
			if (compiled[0] == 0) {
				Log.e(TAG, "Could not compile shader " + shaderType + ":");
				Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
				GLES20.glDeleteShader(shader);
				shader = 0;
			}
		}
		return shader;
	}

	// Delegates.
	
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
//    	ARMovieActivity.nativeSurfaceCreated();

		program = createProgram(vertexShader, this.resolveShader());
		if (program == 0) {
			return;
		}
		aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition");
		checkGlError("glGetAttribLocation aPosition");
		if (aPositionHandle == -1) {
			throw new RuntimeException("Could not get attrib location for aPosition");
		}
		aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
		checkGlError("glGetAttribLocation aTextureCoord");
		if (aTextureHandle == -1) {
			throw new RuntimeException("Could not get attrib location for aTextureCoord");
		}

		uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
		checkGlError("glGetUniformLocation uMVPMatrix");
		if (uMVPMatrixHandle == -1) {
			throw new RuntimeException("Could not get attrib location for uMVPMatrix");
		}

		uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
		checkGlError("glGetUniformLocation uSTMatrix");
		if (uSTMatrixHandle == -1) {
			throw new RuntimeException("Could not get attrib location for uSTMatrix");
		}

		prepareSurface();
    }

	private void prepareSurface() {
		int[] textures = new int[1];
		GLES20.glGenTextures(1, textures, 0);

		textureID = textures[0];
		GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureID);
		checkGlError("glBindTexture textureID");

		GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
				GLES20.GL_NEAREST);
		GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
				GLES20.GL_LINEAR);

		surface = new SurfaceTexture(textureID);
		surface.setOnFrameAvailableListener(this);

		Surface surface = new Surface(this.surface);
		onSurfacePrepareListener.surfacePrepared(surface);

		synchronized(this) {
			updateSurface = false;
		}
	}
	private String resolveShader() {
		return isCustom ? shader : String.format(Locale.ENGLISH, alphaShader,
				redParam, greenParam, blueParam, 1 - accuracy);
	}

    public void onSurfaceChanged(GL10 gl, int w, int h) {
		GLES20.glViewport(0, 0, w, h);
    	ARMovieActivity.nativeSurfaceChanged(w, h);
    }
    public void onDrawFrame(GL10 gl) {
    	
//    	mMovieController.updateTexture();
//
//    	ARMovieActivity.nativeDrawFrame(mMovieController.mMovieWidth, mMovieController.mMovieHeight, mMovieController.mGLTextureID, mMovieController.mGLTextureMtx);

		synchronized(this) {
			if (updateSurface) {
				surface.updateTexImage();
				surface.getTransformMatrix(sTMatrix);
				updateSurface = false;
			}
		}
		GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
		GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

		GLES20.glUseProgram(program);
		checkGlError("glUseProgram");

		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureID);

		triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
		GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false,
				TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices);
		checkGlError("glVertexAttribPointer maPosition");
		GLES20.glEnableVertexAttribArray(aPositionHandle);
		checkGlError("glEnableVertexAttribArray aPositionHandle");

		triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
		GLES20.glVertexAttribPointer(aTextureHandle, 3, GLES20.GL_FLOAT, false,
				TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices);
		checkGlError("glVertexAttribPointer aTextureHandle");
		GLES20.glEnableVertexAttribArray(aTextureHandle);
		checkGlError("glEnableVertexAttribArray aTextureHandle");

		Matrix.setIdentityM(mVPMatrix, 0);
		GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mVPMatrix, 0);
		GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, sTMatrix, 0);

		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
		checkGlError("glDrawArrays");

		GLES20.glFinish();
    }


	@Override
	public void onSurfaceDestroyed(GL10 gl) {

	}

	@Override
	public void onFrameAvailable(SurfaceTexture surfaceTexture) {
updateSurface=true;
	}


	void setAlphaColor(int color) {
		redParam = (float) Color.red(color) / COLOR_MAX_VALUE;
		greenParam = (float) Color.green(color) / COLOR_MAX_VALUE;
		blueParam = (float) Color.blue(color) / COLOR_MAX_VALUE;
	}

	void setAccuracy(double accuracy) {
		if (accuracy > 1.0) {
			accuracy = 1.0;
		} else if (accuracy < 0.0) {
			accuracy = 0.0;
		}
		this.accuracy = accuracy;
	}

	void setCustomShader(String customShader) {
		isCustom = true;
		shader = customShader;
	}
	public double getAccuracy() {
		return accuracy;
	}


	private void checkGlError(String op) {
		int error;
		if ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
			Log.e(TAG, op + ": glError " + error);
			throw new RuntimeException(op + ": glError " + error);
		}
	}
	void setOnSurfacePrepareListener(Renderer.OnSurfacePrepareListener onSurfacePrepareListener) {
		this.onSurfacePrepareListener = onSurfacePrepareListener;
	}

	interface OnSurfacePrepareListener {
		void surfacePrepared(Surface surface);
	}

}
