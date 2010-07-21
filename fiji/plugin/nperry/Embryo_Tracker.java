package fiji.plugin.nperry;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.PointRoi;
import ij.plugin.PlugIn;
import ij.process.StackConverter;
import ij3d.Content;
import ij3d.Image3DUniverse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.ListIterator;

import vib.PointList;

import mpicbg.imglib.algorithm.fft.FourierConvolution;
import mpicbg.imglib.algorithm.extremafinder.RegionalExtremaFactory;
import mpicbg.imglib.algorithm.extremafinder.RegionalExtremaFinder;
import mpicbg.imglib.algorithm.gauss.DownSample;
import mpicbg.imglib.algorithm.gauss.GaussianConvolutionRealType;
import mpicbg.imglib.algorithm.laplace.LoGKernelFactory;
import mpicbg.imglib.algorithm.math.MathLib;
import mpicbg.imglib.algorithm.roi.DirectConvolution;
import mpicbg.imglib.algorithm.roi.MedianFilter;
import mpicbg.imglib.algorithm.roi.StructuringElement;

import mpicbg.imglib.container.array.ArrayContainerFactory;
import mpicbg.imglib.cursor.Cursor;
import mpicbg.imglib.cursor.LocalizableByDimCursor;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.ImageFactory;
import mpicbg.imglib.image.ImagePlusAdapter;
import mpicbg.imglib.image.display.imagej.ImageJFunctions;
import mpicbg.imglib.outofbounds.OutOfBoundsStrategyMirrorFactory;
import mpicbg.imglib.type.logic.BitType;
import mpicbg.imglib.type.numeric.RealType;
import mpicbg.imglib.type.numeric.integer.ShortType;
import mpicbg.imglib.type.numeric.real.FloatType;

/**
 * 
 * @author Nick Perry
 *
 * @param <T>
 */
public class Embryo_Tracker<T extends RealType<T>> implements PlugIn {
	/** Class/Instance variables */
	protected Image<T> img;
	final static protected float GOAL_DOWNSAMPLED_BLOB_DIAM = 10f;				  // trial and error showed that downsizing images so that the blobs have a diameter of 10 pixels performs best (least errors, and most correct finds, by eyeball analysis).
	final static protected double IDEAL_SIGMA_FOR_DOWNSAMPLED_BLOB_DIAM = 1.55f;  // trial and error proved this to be approximately the best sigma for a blob of 10 pixels in diameter.
	
	/** Ask for parameters and then execute. */
	public void run(String arg) {
		// 1 - Obtain the currently active image:
		ImagePlus imp = IJ.getImage();
		if (null == imp) return;
		
		// 2 - Ask for parameters:
		GenericDialog gd = new GenericDialog("Track");
		gd.addNumericField("Generic blob diameter:", 7.3, 2, 5, imp.getCalibration().getUnits());  	// get the expected blob size (in pixels).
		gd.addMessage("Verify calibration settings:");
		gd.addNumericField("Pixel width:", imp.getCalibration().pixelWidth, 3);		// used to calibrate the image for 3D rendering
		gd.addNumericField("Pixel height:", imp.getCalibration().pixelHeight, 3);	// used to calibrate the image for 3D rendering
		gd.addNumericField("Voxel depth:", imp.getCalibration().pixelDepth, 3);		// used to calibrate the image for 3D rendering
		gd.addCheckbox("Use median filter", false);
		gd.addCheckbox("Allow edge maxima", false);
		gd.showDialog();
		if (gd.wasCanceled()) return;

		// 3 - Retrieve parameters from dialogue:
		float diam = (float)gd.getNextNumber();
		float pixelWidth = (float)gd.getNextNumber();
		float pixelHeight = (float)gd.getNextNumber();
		float pixelDepth = (float)gd.getNextNumber();
		boolean useMedFilt = (boolean)gd.getNextBoolean();
		boolean allowEdgeMax = (boolean)gd.getNextBoolean();

		// 4 - Execute!
		Object[] result = exec(imp, diam, useMedFilt, allowEdgeMax, pixelWidth, pixelHeight, pixelDepth);
		
		// 5 - Display new image and overlay maxima
		if (null != result) {
			if (img.getNumDimensions() == 3) {	// If original image is 3D, create a 3D rendering of the image and overlay maxima
				Image3DUniverse univFin = (Image3DUniverse) result[0];
				univFin.show();
			} else {
				PointRoi roi = (PointRoi) result[0];
				imp.setRoi(roi);
				imp.updateAndDraw();
			}
		}
	}
	
	/** Execute the plugin functionality: apply a median filter (for salt and pepper noise), a Gaussian blur, and then find maxima. */
	public Object[] exec(ImagePlus imp, float diam, boolean useMedFilt, boolean allowEdgeMax, float pixelWidth, float pixelHeight, float pixelDepth) {
		// 0 - Check validity of parameters:
		if (null == imp) return null;
		
		// 1 - Prepare for use with Imglib:
		img = ImagePlusAdapter.wrap(imp);
		int numDim = img.getNumDimensions();
		
		// 2 - Downsample to improve run time. The image is downsampled by the factor necessary to achieve a resulting blob size of about 10 pixels in diameter in all dimensions.
		IJ.log("Downsampling...");
		IJ.showStatus("Downsampling...");
		int dim[] = img.getDimensions();
		float downsampleFactors[] = createDownsampledDim(pixelWidth, pixelHeight, pixelDepth, diam);	// factors for x,y,z that we need for scaling image down
		//IJ.log("Downsampling factors: " + MathLib.printCoordinates(downsampleFactors));
		int downsampledDim[] = (numDim == 3) ? new int[]{(int)(dim[0] / downsampleFactors[0]), (int)(dim[1] / downsampleFactors[1]), (int)(dim[2] / downsampleFactors[2])} : new int[]{(int)(dim[0] / downsampleFactors[0]), (int)(dim[1] / downsampleFactors[1])};  // downsampled image dimensions once the downsampleFactors have been applied to their respective image dimensions
		final DownSample<T> downsampler = new DownSample<T>(img, downsampledDim, 0.5f, 0.5f);	// optimal sigma is defined by 0.5f, as mentioned here: http://pacific.mpi-cbg.de/wiki/index.php/Downsample
		if (downsampler.checkInput() && downsampler.process()) {  // checkInput ensures the input is correct, and process runs the algorithm.
			img = downsampler.getResult(); 
		} else { 
	        System.out.println(downsampler.getErrorMessage()); 
	        System.out.println("Bye.");
	        return null;
		}
		
		// 3 - Apply a median filter, to get rid of salt and pepper noise which could be mistaken for maxima in the algorithm (only applied if requested by user explicitly):
		if (useMedFilt) {
			IJ.log("Applying median filter...");
			IJ.showStatus("Applying median filter...");
			StructuringElement strel;
			
			// 3.1 - Need to figure out the dimensionality of the image in order to create a StructuringElement of the correct dimensionality (StructuringElement needs to have same dimensionality as the image):
			if (numDim == 3) {  // 3D case
				strel = new StructuringElement(new int[]{3, 3, 1}, "3D Square");  // unoptimized shape for 3D case. Note here that we manually are making this shape (not using a class method). This code is courtesy of Larry Lindsey
				Cursor<BitType> c = strel.createCursor();  // in this case, the shape is manually made, so we have to manually set it, too.
				while (c.hasNext()) 
				{ 
				    c.fwd(); 
				    c.getType().setOne(); 
				} 
				c.close(); 
			} else {  			// 2D case
				strel = StructuringElement.createCube(2, 3);  // unoptimized shape
			}
			
			// 3.2 - Apply the median filter:
			final MedianFilter<T> medFilt = new MedianFilter<T>(img, strel, new OutOfBoundsStrategyMirrorFactory<T>()); 
			/** note: add back medFilt.checkInput() when it's fixed */
			if (medFilt.process()) {  // checkInput ensures the input is correct, and process runs the algorithm.
				img = medFilt.getResult(); 
			} else { 
		        System.out.println(medFilt.getErrorMessage()); 
		        System.out.println("Bye.");
		        return null;
			}
		}
		
		// #---------------------------------------#
		// #------        Time Trials       -------#
		// #---------------------------------------#
		Image<T> imgResult = img.clone(); 
		//Image<FloatType> imgClone = null;
		long overall = 0;
		long numIterations = 1;
		for (int i = 0; i < numIterations; i++) {
			long startTime = System.currentTimeMillis();	
		/** Approach 1: L x (G x I ) */
		// 4 - Apply a Gaussian filter (code courtesy of Stephan Preibisch). Theoretically, this will make the center of blobs the brightest, and thus easier to find:
		/*IJ.log("Applying Gaussian filter...");
		IJ.showStatus("Applying Gaussian filter...");
		final GaussianConvolutionRealType<T> convGaussian = new GaussianConvolutionRealType<T>(img, new OutOfBoundsStrategyMirrorFactory<T>(), IDEAL_SIGMA_FOR_DOWNSAMPLED_BLOB_DIAM);
		if (convGaussian.checkInput() && convGaussian.process()) {  // checkInput ensures the input is correct, and process runs the algorithm.
			img = convGaussian.getResult(); 
		} else { 
	        System.out.println(convGaussian.getErrorMessage()); 
	        System.out.println("Bye.");
	        return null;
		}
		
		// 5 - Apply a Laplacian convolution to the image.
		IJ.log("Applying Laplacian convolution...");
		IJ.showStatus("Applying Laplacian convolution...");
		// Laplacian kernel construction: everything is negative so that we can use the existing find maxima classes (otherwise, it would be creating minima, and we would need to use find minima). The kernel has everything divided by 18 because we want the highest value to be 1, so that numbers aren't created that beyond the capability of the image type. For example, in a short type, if we had the highest number * 18, the short type can't hold that, and the rest of the value is lost in conversion. This way, we won't create numbers larger than the respective types can hold.
		DirectConvolution<T, FloatType, T> convLaplacian;
		if (numDim == 3) {
			float laplacianArray[][][] = new float[][][]{ { {0,-1/18,0},{-1/18,-1/18,-1/18},{0,-1/18,0} }, { {-1/18,-1/18,-1/18}, {-1/18,1,-1/18}, {-1/18,-1/18,-1/18} }, { {0,-1/18,0},{-1/18,-1/18,-1/18},{0,-1/18,0} } }; // laplace kernel found here: http://en.wikipedia.org/wiki/Discrete_Laplace_operator
			ImageFactory<FloatType> factory = new ImageFactory<FloatType>(new FloatType(), new ArrayContainerFactory());
			Image<FloatType> laplacianKernel = factory.createImage(new int[]{3, 3, 3}, "Laplacian");
			quickKernel3D(laplacianArray, laplacianKernel);
			convLaplacian = new DirectConvolution<T, FloatType, T>(img.createType(), img, laplacianKernel);;
		} else {
			float laplacianArray[][] = new float[][]{ {-1/8,-1/8,-1/8},{-1/8,1,-1/8},{-1/8,-1/8,-1/8} }; // laplace kernel found here: http://en.wikipedia.org/wiki/Discrete_Laplace_operator
			ImageFactory<FloatType> factory = new ImageFactory<FloatType>(new FloatType(), new ArrayContainerFactory());
			Image<FloatType> laplacianKernel = factory.createImage(new int[]{3, 3}, "Laplacian");
			quickKernel2D(laplacianArray, laplacianKernel);
			convLaplacian = new DirectConvolution<T, FloatType, T>(img.createType(), img, laplacianKernel);;
		}
		//if(convLaplacian.checkInput() && convLaplacian.process()) {
		if(convLaplacian.process()) {
			img = convLaplacian.getResult();
			//ImagePlus test = ImageJFunctions.copyToImagePlus(img);
			//test.show();
		} else {
			System.out.println(convLaplacian.getErrorMessage());
			System.out.println("Bye.");
			return null;
		}*/
		
		/** Approach 2: F(L) x F(G) x F(I) */
		
		// Gauss
		IJ.log("Applying Gaussian filter...");
		IJ.showStatus("Applying Gaussian filter...");
		ImageFactory<FloatType> factory = new ImageFactory<FloatType>(new FloatType(), new ArrayContainerFactory());
		Image<FloatType> gaussKernel = FourierConvolution.getGaussianKernel(factory, IDEAL_SIGMA_FOR_DOWNSAMPLED_BLOB_DIAM, numDim);
		FourierConvolution<T, FloatType> fConvGauss = new FourierConvolution<T, FloatType>(img, gaussKernel);
		if (!fConvGauss.checkInput() || !fConvGauss.process()) {
			System.out.println( "Fourier Convolution failed: " + fConvGauss.getErrorMessage() );
			return null;
		}
		img = fConvGauss.getResult();
		
		// Laplace
		IJ.log("Applying Laplacian convolution...");
		IJ.showStatus("Applying Laplacian convolution...");
		Image<FloatType> laplacianKernel;
		if (numDim == 3) {
			float laplacianArray[][][] = new float[][][]{ { {0,-1/18,0},{-1/18,-1/18,-1/18},{0,-1/18,0} }, { {-1/18,-1/18,-1/18}, {-1/18,1,-1/18}, {-1/18,-1/18,-1/18} }, { {0,-1/18,0},{-1/18,-1/18,-1/18},{0,-1/18,0} } }; // laplace kernel found here: http://en.wikipedia.org/wiki/Discrete_Laplace_operator
			laplacianKernel = factory.createImage(new int[]{3, 3, 3}, "Laplacian");
			quickKernel3D(laplacianArray, laplacianKernel);
		} else {
			float laplacianArray[][] = new float[][]{ {-1/8,-1/8,-1/8},{-1/8,1,-1/8},{-1/8,-1/8,-1/8} }; // laplace kernel found here: http://en.wikipedia.org/wiki/Discrete_Laplace_operator
			laplacianKernel = factory.createImage(new int[]{3, 3}, "Laplacian");
			quickKernel2D(laplacianArray, laplacianKernel);
		}
		FourierConvolution<T, FloatType> fConvLaplacian = new FourierConvolution<T, FloatType>(img, laplacianKernel);
		if (!fConvLaplacian.checkInput() || !fConvLaplacian.process()) {
			System.out.println( "Fourier Convolution failed: " + fConvLaplacian.getErrorMessage() );
			return null;
		}
		img = fConvLaplacian.getResult();		
		
		/** Approach 3: (L x G) x I */
		/*IJ.log("Applying LoG Convolution...");
		Image<FloatType> logKern = LoGKernelFactory.createLoGKernel(IDEAL_SIGMA_FOR_DOWNSAMPLED_BLOB_DIAM, numDim, true, true);
		DirectConvolution<T, FloatType, T> convLoG = new DirectConvolution<T, FloatType, T>(img.createType(), img, logKern);
		//DirectConvolution<T, FloatType, FloatType> convLoG = new DirectConvolution<T, FloatType, FloatType>(new FloatType(), img, logKern);
		if(convLoG.process()) {
			imgResult = convLoG.getResult();
			ImagePlus test = ImageJFunctions.copyToImagePlus(imgResult);
			test.show();
		} else {
			System.out.println(convLoG.getErrorMessage());
			System.out.println("Bye.");
			return null;
		}*/
		
		long runTime = System.currentTimeMillis() - startTime;	
		System.out.println("Laplacian/Gaussian Run Time: " + runTime);
		
		
		/** Approach 4: DoG */
		/** Approach 5: F(DoG) */
		
			//if (i == 0) img = imgClone.clone();
			overall += runTime;
		}
		System.out.println("Average run time: " + (long)overall/numIterations);
		// #----------------------------------------#
		// #------        /Time Trials       -------#
		// #----------------------------------------#
		
		
		// 5 - Find maxima of newly convoluted image:
		IJ.log("Finding maxima...");
		IJ.showStatus("Finding maxima...");
		ArrayList< ArrayList< int[]> > maxima = null;
		RegionalExtremaFactory<T> maxFactory = new RegionalExtremaFactory<T>(img, false);
		RegionalExtremaFinder<T> findMax = maxFactory.createRegionalMaximaFinder();
		//RegionalMaximaFactory<FloatType> maxFactory = new RegionalMaximaFactory<FloatType>(imgClone, false);
		//RegionalMaximaFinder<FloatType> findMax = maxFactory.createRegionalMaximaFinder();
		findMax.allowEdgeExtrema(allowEdgeMax);
		findMax.findMaxima();
		if (findMax.checkInput() && findMax.process()) {  // checkInput ensures the input is correct, and process runs the algorithm.
			maxima = findMax.getRegionalMaxima(); 
		}
		ArrayList< double[] > centeredMaxima = findMax.getRegionalMaximaCenters(maxima);
		System.out.println("Find Maxima Run Time: " + findMax.getProcessingTime());
		System.out.println("Num regional maxima: " + centeredMaxima.size());

		// 6 - Setup for displaying results
		if (numDim == 3) {  // prepare 3D render
			ij.plugin.Duplicator d = new ij.plugin.Duplicator();  // Make a duplicate image so we don't alter the users image when displaying 3D (requires 8-bit, etc).
			ImagePlus duplicated = d.run(imp);
			Image3DUniverse univ = 	render3DAndOverlayMaxima(centeredMaxima, duplicated, pixelWidth, pixelHeight, pixelDepth, downsampleFactors);
			return new Object[]{univ};
		} else {
			PointRoi roi = preparePointRoi(centeredMaxima, downsampleFactors, pixelWidth, pixelHeight);
			return new Object[]{roi};
		}
	}
	
	/**
	 * 
	 * @param pixelWidth
	 * @param pixelHeight
	 * @param pixelDepth
	 * @param diam
	 * @return
	 */
	public float[] createDownsampledDim(float pixelWidth, float pixelHeight, float pixelDepth, float diam) {
		float widthFactor = (diam / pixelWidth) > GOAL_DOWNSAMPLED_BLOB_DIAM ? (diam / pixelWidth) / GOAL_DOWNSAMPLED_BLOB_DIAM : 1;
		float heightFactor = (diam / pixelHeight) > GOAL_DOWNSAMPLED_BLOB_DIAM ? (diam / pixelHeight) / GOAL_DOWNSAMPLED_BLOB_DIAM : 1;
		float depthFactor = (img.getNumDimensions() == 3 && (diam / pixelDepth) > GOAL_DOWNSAMPLED_BLOB_DIAM) ? (diam / pixelDepth) / GOAL_DOWNSAMPLED_BLOB_DIAM : 1;								
		float downsampleFactors[] = new float[]{widthFactor, heightFactor, depthFactor};
		
		return downsampleFactors;
	}
	
	/**
	 * 
	 * @param vals
	 * @param kern
	 */
	protected static void quickKernel3D(float[][][] vals, Image<FloatType> kern)
	{
		final LocalizableByDimCursor<FloatType> cursor = kern.createLocalizableByDimCursor();
		final int[] pos = new int[3];

		for (int i = 0; i < vals.length; ++i)
		{
			for (int j = 0; j < vals[i].length; ++j)
			{
				for (int k = 0; k < vals[j].length; ++k)
				{
					pos[0] = i;
					pos[1] = j;
					pos[2] = k;
					cursor.setPosition(pos);
					cursor.getType().set(vals[i][j][k]);
				}
			}
		}
		cursor.close();		
	}
	
	/**
	 * Code courtesy of Larry Lindsey. However, it is protected in the DirectConvolution class,
	 * so I reproduced it here to avoid instantiating an object.
	 * 
	 * @param vals
	 * @param kern
	 */
	protected static void quickKernel2D(float[][] vals, Image<FloatType> kern)
	{
		final LocalizableByDimCursor<FloatType> cursor = kern.createLocalizableByDimCursor();
		final int[] pos = new int[2];

		for (int i = 0; i < vals.length; ++i)
		{
			for (int j = 0; j < vals[i].length; ++j)
			{
				pos[0] = i;
				pos[1] = j;
				cursor.setPosition(pos);
				cursor.getType().set(vals[i][j]);
			}
		}
		cursor.close();		
	}
	
	/**
	 * 
	 * @param maxima
	 * @param downsamplingFactor
	 * @return
	 */
	public PointRoi preparePointRoi (ArrayList< double[] > maxima, float downsampleFactors[], float pixelWidth, float pixelHeight) {
		int numPoints = maxima.size();
		int ox[] = new int[numPoints];
		int oy[] = new int[numPoints];
		ListIterator< double[] > itr = maxima.listIterator();
		int index = 0;
		while (itr.hasNext()) {
			double curr[] = itr.next();
			ox[index] = (int) (curr[0] * downsampleFactors[0]);
			oy[index] = (int) (curr[1] * downsampleFactors[1]);
			index++;
		}
		PointRoi roi = new PointRoi(ox, oy, numPoints);
		return roi;
	}
	
	/**
	 * 
	 * @param maxima
	 * @param scaled
	 * @param pixelWidth
	 * @param pixelHeight
	 * @param pixelDepth
	 */
	public Image3DUniverse render3DAndOverlayMaxima(ArrayList< double[] > maxima, ImagePlus scaled, float pixelWidth, float pixelHeight, float pixelDepth, float downsampleFactors[]) {
		// Adjust calibration
		scaled.getCalibration().pixelWidth = pixelWidth;
		scaled.getCalibration().pixelHeight = pixelHeight;
		scaled.getCalibration().pixelDepth = pixelDepth;
		
		// Convert to a usable format
		new StackConverter(scaled).convertToGray8();
		
		// Create a universe, but do not show it
		Image3DUniverse univ = new Image3DUniverse();
		
		// Add the image as a volume rendering
		Content c = univ.addVoltex(scaled);

		// Change the size of the points
		float curr = c.getLandmarkPointSize();
		c.setLandmarkPointSize(curr/9);
		
		// Retrieve the point list
		PointList pl = c.getPointList();
		
		// Add maxima as points to the point list
		Iterator< double[] > itr = maxima.listIterator();
		while (itr.hasNext()) {
			double maxCoords[] = itr.next();
			//int debug[] = new int[] {(int)maxCoords[0], (int)maxCoords[1], (int)maxCoords[2]};
			//IJ.log(MathLib.printCoordinates(debug));
			pl.add(maxCoords[0] * downsampleFactors[0] * pixelWidth, maxCoords[1] * downsampleFactors[1] * pixelHeight, maxCoords[2] * downsampleFactors[2] * pixelDepth);
		}
		

		// Make the point list visible
		c.showPointList(true);
		
		return univ;
	}
}

/** ---------------------------- */
/**         archived code        */
/** ---------------------------- */

/** Tried implementing findMaxima using a trick from Michael Schmid's version of 'Find Maxima' that comes with ImageJ to speed my version up. Ultimately, my version above and "his version" (not quite his, but as best I could) produced the same performance.  */
/*public void findMaxima2D(Image<T> img) {
long start = System.currentTimeMillis();
// 1 - Initialize local variables, cursors
final LocalizableByDimCursor<T> curr = img.createLocalizableByDimCursor(new OutOfBoundsStrategyMirrorFactory<T>()); // this cursor is the main cursor which iterates over all the pixels in the image.  
LocalizableByDimCursor<T> local = img.createLocalizableByDimCursor(new OutOfBoundsStrategyMirrorFactory<T>());		// this cursor is used to search a connected "lake" of pixels, or pixels with the same value
LocalNeighborhoodCursor<T> neighbors = new LocalNeighborhoodCursor<T>(local);										// this cursor is used to search the immediate neighbors of a pixel
ArrayList< int[] > maxCoordinates = new ArrayList< int[] >();  	// holds the positions of the local maxima
T currentValue = img.createType();  							// holds the value of the current pixel's intensity. We use createType() here because getType() gives us a pointer to the cursor's object, but since the neighborhood moves the parent cursor, when we check the neighbors, we actually change the object stored here, or the pixel we are trying to compare to. see fiji-devel list for further explanation.
T neighborValue; 												// holds the value of the neighbor's intensity
int width = img.getDimensions()[0];								// width of the image. needed for storing info in the visited and visitedLakeMember arrays correctly
byte visited[] = new byte[img.getNumPixels()];					// stores whether or not this pixel has been searched either by the main cursor, or directly in a lake search.
boolean isMax;													// flag which tells us whether the current lake is a local max or not
int currCoords[] = new int[2];									// coords of outer, main loop
int neighborCoords[] = new int[2];								// coords of neighbor
int averagedMaxPos[] = new int[2];								// for a local max lake, this stores the 'center' of the lake's position.

int pList[] = new int[img.getNumPixels()];

// 2 - Search all pixels for LOCAL maxima. A local maximum is a pixel that is the brightest in its immediate neighborhood (so the pixel is brighter or as bright as the 26 direct neighbors of it's cube-shaped neighborhood if 3D). If neighboring pixels have the same value as the current pixel, then the pixels are treated as a local "lake" and the lake is searched to determine whether it is a maximum "lake" or not.

// 2.1 - Iterate over all pixels in the image.
while(curr.hasNext()) { 
	int listI = 0;
	int listLen = 1;
	curr.fwd();
	curr.getPosition(currCoords);
	if ((visited[getIndexOfPosition2D(currCoords, width)] & PROCESSED) != 0) {	// if we've already seen this pixel, then we've already decided if its a max or not, so skip it
		continue;
	}
	isMax = true;  				// this pixel could be a max
	pList[listI] = getIndexOfPosition2D(currCoords, width);
	// 2.2 - Iterate through queue which contains the pixels of the "lake"
	do {
		int offset = pList[listI];
		int x = offset % width;
		int y = offset / width;
		if ((visited[offset] & PROCESSED) != 0) {	// prevents us from just searching the lake infinitely
			listI++;
			continue;
		} else {	// if we've never seen, add to visited list, and add to searched list.
			visited[offset] |= PROCESSED;	
		}
		local.setPosition(new int[] {x, y});		// set the local cursors position to the next member of the lake, so that the neighborhood cursor can search its neighbors.
		currentValue.set(local.getType());  // store the value of this pixel in a variable
		neighbors.update();
		while(neighbors.hasNext()) {
			neighbors.fwd();
			neighborCoords = local.getPosition();
			neighborValue = neighbors.getType();
			
			// Case 1: neighbor's value is strictly larger than ours, so ours cannot be a local maximum.
			if (neighborValue.compareTo(currentValue) > 0) {
				isMax = false;
			}
			
			// Case 2: neighbor's value is strictly equal to ours, which means we could still be at a maximum, but the max value is a blob, not just a single point. We must check the area. In other words, we have a lake.
			else if (neighborValue.compareTo(currentValue) == 0 && isWithinImageBounds2D(neighborCoords) && (visited[getIndexOfPosition2D(neighborCoords, width)] & VISITED) == 0) {
				pList[listLen] = getIndexOfPosition2D(neighborCoords, width);
				visited[getIndexOfPosition2D(neighborCoords, width)] |= VISITED;  // prevents us from adding the same thing to the pList multiple times.
				listLen++;
			}
		}
		neighbors.reset();
		listI++;
	} while (listI < listLen);
	if (isMax) {	// if we get here, we've searched the entire lake, so find the average point and call that a max by adding to results list
		averagedMaxPos = findAveragePosition2D(pList, listLen, width);
		maxCoordinates.add(averagedMaxPos);
	}
}
//IJ.log("done searching!");
curr.close();
neighbors.close();

long deltaT = System.currentTimeMillis() - start;

// 3 - Print out list of maxima, set up for point display (FOR TESTING):
ox = new int[maxCoordinates.size()];
oy = new int[maxCoordinates.size()];
points = maxCoordinates.size();
int index = 0;
String img_dim = MathLib.printCoordinates(img.getDimensions());
IJ.log("Image dimensions: " + img_dim);
Iterator<int[]> itr = maxCoordinates.iterator();
while (itr.hasNext()) {
	int coords[] = itr.next();
	ox[index] = coords[0];
	oy[index] = coords[1];
	String pos_str = MathLib.printCoordinates(coords);
	IJ.log(pos_str);
	index++;
}
}*/

/*public int[] findAveragePosition2D(int pList[], int listLen, int width) {
int count = 0;
int avgX = 0, avgY = 0;
for (int i = 0; i < listLen; i++) {
	int curr = pList[i];
	avgX += curr % width;
	avgY += curr / width;
	count++;
}
return new int[] {avgX/count, avgY/count};
}*/