package org.area515.resinprinter.display.dispmanx;

import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.nio.ByteBuffer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.area515.resinprinter.display.CustomNamedDisplayDevice;
import org.area515.resinprinter.display.GraphicsOutputInterface;
import org.area515.resinprinter.display.InappropriateDeviceException;
import org.area515.util.Log4jTimer;

import com.sun.jna.Memory;
import com.sun.jna.ptr.IntByReference;

public class DispManXDevice extends CustomNamedDisplayDevice implements GraphicsOutputInterface {
	private static final String IMAGE_REALIZE_TIMER = "Image Realize";
    private static final Logger logger = LogManager.getLogger();
    private static boolean BCM_INIT = false;
    
    private Rectangle bounds = new Rectangle();
    private SCREEN screen;
    private VC_DISPMANX_ALPHA_T.ByReference alpha;
    private int displayHandle;
    private boolean screenInitialized = false;
    
    //For dispmanx
    private int imageResourceHandle;
    private int imageElementHandle;
    //For Image
    private Memory imagePixels;
    private int imageWidth;
    private int imageHeight;
    //For Calibration and Grid
    private Memory calibrationAndGridPixels;
    private BufferedImage calibrationAndGridImage;
    
    public DispManXDevice(String displayName, SCREEN screen) throws InappropriateDeviceException {
		super(displayName);
		this.screen = screen;
	}
    
    private synchronized static void bcmHostInit() {
    	if (BCM_INIT) {
    		return;
    	}
    	
    	logger.info("initialize bcm host");
    	int returnCode = DispManX.INSTANCE.bcm_host_init();
    	if (returnCode != 0) {
    		throw new IllegalArgumentException("bcm_host_init failed with:" + returnCode);
    	}
    	BCM_INIT = true;
    }
    
    private synchronized void initializeScreen() {
    	if (screenInitialized) {
    		return;
    	}
    	logger.info("initialize screen");
    	bcmHostInit();
    	
        IntByReference width = new IntByReference();
        IntByReference height = new IntByReference();
    	int returnCode = DispManX.INSTANCE.graphics_get_display_size(screen.getId(), width, height);
    	if (returnCode != 0) {
    		throw new IllegalArgumentException("graphics_get_display_size failed with:" + returnCode);
    	}
    	bounds.setBounds(0, 0, width.getValue(), height.getValue());
    	
    	displayHandle = DispManX.INSTANCE.vc_dispmanx_display_open(screen.getId());
    	if (displayHandle == 0) {
    		throw new IllegalArgumentException("vc_dispmanx_display_open failed with:" + returnCode);
    	}
    	
        VC_DISPMANX_ALPHA_T.ByReference alpha = new VC_DISPMANX_ALPHA_T.ByReference();
        alpha.flags = ALPHA.DISPMANX_FLAGS_ALPHA_FROM_SOURCE.getFlag() | ALPHA.DISPMANX_FLAGS_ALPHA_FIXED_ALL_PIXELS.getFlag();
        alpha.opacity = 255;
        screenInitialized = true;
    }
    
	@Override
	public synchronized void dispose() {
		logger.info("dispose screen");
		removeAllElementsFromScreen();
    	logger.info("vc_dispmanx_display_close result:" + DispManX.INSTANCE.vc_dispmanx_display_close(displayHandle));
    	calibrationAndGridPixels = null;
    	imagePixels = null;
    	calibrationAndGridImage = null;
    	imageWidth = 0;
    	imageHeight = 0;
    	screenInitialized = false;
	}
	
    public static int getPitch( int x, int y ) {
        return ((x + (y)-1) & ~((y)-1));
    }
    
	private Memory loadBitmapRGB565(BufferedImage image, Memory destPixels, IntByReference width, IntByReference height, IntByReference pitchByRef) {
		int bytesPerPixel = 2;
		int pitch = getPitch(bytesPerPixel * image.getWidth(), 32);
		pitchByRef.setValue(pitch);
		if (destPixels == null) {
			destPixels = new Memory(pitch * image.getHeight());
		}
		
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
        		int rgb = image.getRGB(x, y);
        		destPixels.setShort((y*(pitch / bytesPerPixel) + x) * bytesPerPixel, (short)(((rgb & 0xf80000) >>> 8) | ((rgb & 0xfc00) >>> 5) | (rgb & 0xf8 >>> 3)));
            }
        }
        width.setValue(image.getWidth());
        height.setValue(image.getHeight());
        return destPixels;
	}

	private Memory loadBitmapARGB8888(BufferedImage image, Memory destPixels, IntByReference width, IntByReference height, IntByReference pitchByRef) {
		int bytesPerPixel = 4;
		int pitch = getPitch(bytesPerPixel * image.getWidth(), 32);
		pitchByRef.setValue(pitch);
		if (destPixels == null) {
			destPixels = new Memory(pitch * image.getHeight());
		}
		
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
        		destPixels.setInt((y*(pitch / bytesPerPixel) + x) * bytesPerPixel, image.getRGB(x, y));
            }
        }
        width.setValue(image.getWidth());
        height.setValue(image.getHeight());
        return destPixels;
	}
	
	//TODO: this is totally untested but wouldn't it be cool if we could write straight to the native screen buffer from a BufferedImage without the need to use loadBitmapARGB8888 and loadBitmapRGB565!
	private BufferedImage createBufferedImage(Memory pixelMemory, int xPixels, int yPixels) {
		final ByteBuffer buffer = pixelMemory.getByteBuffer(0, pixelMemory.size());
		DataBuffer nativeScreenBuffer = new DataBuffer(DataBuffer.TYPE_INT, buffer.limit()) {
			@Override
			public int getElem(int bank, int i) {
				return (buffer.get(i * 4) << 24) | (buffer.get(i * 4 + 1) << 16) | (buffer.get(i * 4 + 2) << 8) | (buffer.get(i * 4 + 3));
			}
		  
			@Override
			public void setElem(int bank, int i, int val) {
				buffer.put(i * 4 + 0, (byte)((val | 0xFF000000) >> 24));
				buffer.put(i * 4 + 1, (byte)((val | 0xFF0000) >> 16));
				buffer.put(i * 4 + 2, (byte)((val | 0xFF00) >> 8));
				buffer.put(i * 4 + 3, (byte)(val | 0xFF));
			}
		};
	
		SampleModel argb = new SinglePixelPackedSampleModel(
		    DataBuffer.TYPE_INT, 
		    xPixels, 
		    yPixels,
		    new int[] { 0xFF0000, 0xFF00, 0xFF, 0xFF000000 });
		
		WritableRaster raster = new WritableRaster(argb, nativeScreenBuffer, new Point()){};
		
		return new BufferedImage(
		    new DirectColorModel(32, 0xFF0000, 0xFF00, 0xFF0, 0xFF000000),
		    raster, 
		    false, 
		    null);
	}

	@Override
	public void showBlankImage() {
		initializeScreen();
		removeAllElementsFromScreen();
	}

	private void removeAllElementsFromScreen() {
		logger.info("screen cleanup started");
        int updateHandle = DispManX.INSTANCE.vc_dispmanx_update_start( 0 );
        if (updateHandle == 0) {
        	logger.info("vc_dispmanx_update_start failed");
        } else {
        	logger.debug("image vc_dispmanx_element_remove result:" + DispManX.INSTANCE.vc_dispmanx_element_remove(updateHandle, imageElementHandle));
        	logger.debug("vc_dispmanx_update_submit_sync result:" + DispManX.INSTANCE.vc_dispmanx_update_submit_sync(updateHandle));
        	logger.debug("image vc_dispmanx_resource_delete result:" + DispManX.INSTANCE.vc_dispmanx_resource_delete(imageResourceHandle));
        }
	}
	
	private void initializeCalibrationAndGridImage() {
		if (calibrationAndGridImage != null) {
			return;
		}
		
		calibrationAndGridImage = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
	}
	
	@Override
	public void showCalibrationImage(int xPixels, int yPixels) {
		logger.debug("Calibration assigned:{}", () -> Log4jTimer.startTimer(IMAGE_REALIZE_TIMER));
		showBlankImage();
		initializeCalibrationAndGridImage();
		Graphics2D graphics = (Graphics2D)calibrationAndGridImage.createGraphics();
		GraphicsOutputInterface.showCalibration(graphics, bounds, xPixels, yPixels);
		graphics.dispose();
		calibrationAndGridPixels = showImage(calibrationAndGridPixels, calibrationAndGridImage);
		logger.debug("Calibration realized:{}", () -> Log4jTimer.completeTimer(IMAGE_REALIZE_TIMER));
	}
	
	@Override
	public void showGridImage(int pixels) {
		logger.debug("Grid assigned:{}", () -> Log4jTimer.startTimer(IMAGE_REALIZE_TIMER));
		showBlankImage();
		initializeCalibrationAndGridImage();
		Graphics2D graphics = (Graphics2D)calibrationAndGridImage.createGraphics();
		GraphicsOutputInterface.showGrid(graphics, bounds, pixels);
		graphics.dispose();
		
		calibrationAndGridPixels = showImage(calibrationAndGridPixels, calibrationAndGridImage);		
		logger.debug("Grid realized:{}", () -> Log4jTimer.completeTimer(IMAGE_REALIZE_TIMER));
	}
	
	private Memory showImage(Memory memory, BufferedImage image) {
		showBlankImage();//delete the old resources because we are creating new ones...
		
        IntByReference imageWidth = new IntByReference();
        IntByReference imageHeight = new IntByReference();
        IntByReference imagePitch = new IntByReference();
        
        memory = loadBitmapARGB8888(image, memory, imageWidth, imageHeight, imagePitch);
        VC_RECT_T.ByReference sourceRect = new VC_RECT_T.ByReference();
        DispManX.INSTANCE.vc_dispmanx_rect_set(sourceRect, 0, 0, imageWidth.getValue()<<16, imageHeight.getValue()<<16);//Shifting by 16 is a zoom factor of zero
        
        IntByReference nativeImageReference = new IntByReference();
        imageResourceHandle = DispManX.INSTANCE.vc_dispmanx_resource_create(
        		VC_IMAGE_TYPE_T.VC_IMAGE_ARGB8888.getcIndex(), 
        		imageWidth.getValue(), 
        		imageHeight.getValue(), 
        		nativeImageReference);
        if (imageResourceHandle == 0) {
        	throw new IllegalArgumentException("Couldn't create resourceHandle for dispmanx");
        }
        
        VC_RECT_T.ByReference sizeRect = new VC_RECT_T.ByReference();
        DispManX.INSTANCE.vc_dispmanx_rect_set(sizeRect, 0, 0, imageWidth.getValue(), imageHeight.getValue());
        int returnCode = DispManX.INSTANCE.vc_dispmanx_resource_write_data( 
        		imageResourceHandle, 
        		VC_IMAGE_TYPE_T.VC_IMAGE_ARGB8888.getcIndex(), 
        		imagePitch.getValue() , 
        		memory, 
        		sizeRect);
        if (returnCode != 0) {
        	throw new IllegalArgumentException("Couldn't vc_dispmanx_resource_write_data for dispmanx:" + returnCode);
        }
        
        int updateHandle = DispManX.INSTANCE.vc_dispmanx_update_start(0);  //This method should be called create update
        if (updateHandle == 0) {
        	throw new IllegalArgumentException("Couldn't vc_dispmanx_update_start for dispmanx");
        }

        VC_RECT_T.ByReference destinationRect = new VC_RECT_T.ByReference();
        DispManX.INSTANCE.vc_dispmanx_rect_set(
        		destinationRect, 
        		(bounds.width - imageWidth.getValue()) / 2, 
        		(bounds.height - imageHeight.getValue()) / 2, 
        		imageWidth.getValue(), 
        		imageHeight.getValue());
        imageElementHandle = DispManX.INSTANCE.vc_dispmanx_element_add(     //Creates and adds the element to the current screen update
        		updateHandle, 
        		displayHandle, 
        		1, 
        		destinationRect, 
        		imageResourceHandle, 
        		sourceRect, 
        		PROTECTION.DISPMANX_PROTECTION_NONE.getcConst(), 
        		alpha, 
        		0, 
        		VC_IMAGE_TRANSFORM_T.VC_IMAGE_ROT0.getcConst());
        if (updateHandle == 0) {
        	throw new IllegalArgumentException("Couldn't vc_dispmanx_element_add for dispmanx");
        }

        returnCode = DispManX.INSTANCE.vc_dispmanx_update_submit_sync(updateHandle);//Wait for the update to complete
        if (returnCode != 0) {
        	throw new IllegalArgumentException("Couldn't vc_dispmanx_update_submit_sync for dispmanx:" + returnCode);
        }
        
        return memory;
	}
	
	@Override
	public void showImage(BufferedImage image) {
		logger.debug("Image assigned:{}", () -> Log4jTimer.startTimer(IMAGE_REALIZE_TIMER));
		if (image.getWidth() == imageWidth && image.getHeight() == imageHeight) {
			imagePixels = showImage(imagePixels, image);
		} else {
			imagePixels = showImage(null, image);
		}
		imageWidth = image.getWidth();
		imageHeight = image.getHeight();
		logger.debug("Image realized:{}", () -> Log4jTimer.completeTimer(IMAGE_REALIZE_TIMER));
	}
	
	@Override
	public GraphicsConfiguration getDefaultConfiguration() {
		//TODO: this is horrible! we return this fake graphics configuration just so that we can give people our bounds!
		return new GraphicsConfiguration() {
			@Override
			public AffineTransform getNormalizingTransform() {
				return null;
			}
			
			@Override
			public GraphicsDevice getDevice() {
				return null;
			}
			
			@Override
			public AffineTransform getDefaultTransform() {
				return null;
			}
			
			@Override
			public ColorModel getColorModel(int transparency) {
				return null;
			}
			
			@Override
			public ColorModel getColorModel() {
				return null;
			}
			
			@Override
			public Rectangle getBounds() {
				initializeScreen();
				return bounds;
			}
		};
	}

	@Override
	public void resetSliceCount() {
		//Since this isn't used for debugging we don't do anything
	}

	@Override
	public Rectangle getBoundry() {
		initializeScreen();
		return bounds;
	}
}
