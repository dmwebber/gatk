package org.broadinstitute.sting.gatk.walkers.indels;

import org.broadinstitute.sting.gatk.walkers.ReadFilters;
import org.broadinstitute.sting.gatk.walkers.ReadWalker;
import org.broadinstitute.sting.gatk.filters.Platform454Filter;
import org.broadinstitute.sting.gatk.filters.ZeroMappingQualityReadFilter;
import org.broadinstitute.sting.gatk.filters.PlatformUnitFilter;
import org.broadinstitute.sting.gatk.filters.PlatformUnitFilterHelper;
import org.broadinstitute.sting.gatk.refdata.*;
import org.broadinstitute.sting.utils.cmdLine.Argument;
import org.broadinstitute.sting.utils.*;
import org.broadinstitute.sting.playground.utils.CircularArray;

import java.util.*;
import java.io.IOException;

import net.sf.samtools.SAMRecord;
import net.sf.samtools.Cigar;
import net.sf.samtools.CigarOperator;
import net.sf.samtools.CigarElement;

/**
 * Created by IntelliJ IDEA.
 * User: asivache
 * Date: Oct 15, 2009
 * Time: 2:03:03 PM
 * To change this template use File | Settings | File Templates.
 */
@ReadFilters({Platform454Filter.class, ZeroMappingQualityReadFilter.class, PlatformUnitFilter.class})
public class IndelGenotyperV2Walker extends ReadWalker<Integer,Integer> {
    @Argument(fullName="outputFile", shortName="O", doc="output file name (defaults to BED format)", required=true)
    java.io.File bed_file;
    @Argument(fullName="1kg_format", shortName="1kg", doc="output in 1000 genomes format", required=false)
    boolean FORMAT_1KG = false;
	@Argument(fullName="somatic", shortName="somatic",
			doc="Perform somatic calls; two input alignment files must be specified", required=false)
	boolean call_somatic = false;
	@Argument(fullName="verbose", shortName="verbose",
			doc="Tell us what you are calling now (printed to stdout)", required=false)
	boolean verbose = false;
	@Argument(fullName="minCoverage", shortName="minCoverage",
			doc="must have minCoverage or more reads to call indel; with --somatic this value is applied to tumor sample", required=false)
	int minCoverage = 6;
	@Argument(fullName="minNormalCoverage", shortName="minNormalCoverage",
			doc="used only with --somatic;  normal sample must have at least minNormalCoverage or more reads to call germline/somatic indel", required=false)
	int minNormalCoverage = 4;
	@Argument(fullName="minFraction", shortName="minFraction",
			doc="Minimum fraction of reads with CONSENSUS indel at a site, out of all reads covering the site, required for a consensus call"+
			" (fraction of non-consensus indels at the site is not considered here, see minConsensusFraction)", required=false)
	double minFraction = 0.3;
	@Argument(fullName="minConsensusFraction", shortName="minConsensusFraction",
			doc="Minimum fraction of CONSENSUS indel observations at a site wrt all indel observations at the site required to make the call", required=false)
	double minConsensusFraction = 0.7;
	@Argument(fullName="minIndelCount", shortName="minCnt",
			doc="Minimum count of reads supporting consensus indel required for making the call. "+
			" This filter supercedes minFraction, i.e. indels with acceptable minFraction at low coverage "+
			"(minIndelCount not met) will not pass.", required=false)
	int minIndelCount = 0;
	@Argument(fullName="refseq", shortName="refseq",
			doc="Name of RefSeq transcript annotation file. If specified, indels will be annotated as GENOMIC/UTR/INTRON/CODING", required=false)
	String RefseqFileName = null;
    @Argument(fullName="blacklistedLanes", shortName="BL",
            doc="Name of lanes (platform units) that should be ignored. Reads coming from these lanes will never be seen "+
                    "by this application, so they will not contribute indels to consider and will not be counted.", required=false)
    PlatformUnitFilterHelper dummy;
     @Argument(fullName="indel_debug", shortName="idebug", doc="Detailed printout for debugging",required=false) Boolean DEBUG = false;

	private static int WINDOW_SIZE = 200;
	private WindowContext tumor_context;
	private WindowContext normal_context; 
	private int currentContigIndex = -1;
	private int currentPosition = -1; // position of the last read we've seen on the current contig
	private String refName = null;
	private java.io.Writer output = null;
	private GenomeLoc location = null;

    private SeekableRODIterator<rodRefSeq> refseqIterator=null;

	private Set<String> normalReadGroups; // we are going to remember which read groups are normals and which are tumors in order to be able 
	private Set<String> tumorReadGroups ; // to properly assign the reads coming from a merged stream

	private int NQS_WIDTH = 5; // 5 bases on each side of the indel for NQS-style statistics


	private static String annGenomic = "GENOMIC";
	private static String annIntron = "INTRON";
	private static String annUTR = "UTR";
	private static String annCoding = "CODING";
	private static String annUnknown = "UNKNOWN";

	private SAMRecord lastRead;

	// "/humgen/gsa-scr1/GATK_Data/refGene.sorted.txt"

	@Override
	public void initialize() {
		normal_context = new WindowContext(0,WINDOW_SIZE);

		if ( RefseqFileName != null ) {
			ReferenceOrderedData<rodRefSeq> refseq = new ReferenceOrderedData<rodRefSeq>("refseq",
					new java.io.File(RefseqFileName), rodRefSeq.class);

			refseqIterator = refseq.iterator();
			logger.info("Using RefSeq annotations from "+RefseqFileName);
		}

		if ( refseqIterator == null ) logger.info("No annotations available");

		int nSams = getToolkit().getArguments().samFiles.size();

		location = GenomeLocParser.createGenomeLoc(0,1);

		List<Set<String>> readGroupSets = getToolkit().getMergedReadGroupsByReaders();

		if ( call_somatic ) {
			if ( nSams != 2 ) {
				System.out.println("In --somatic mode two input bam files must be specified (normal/tumor)");
				System.exit(1);
			}
			tumor_context = new WindowContext(0,WINDOW_SIZE);

			normalReadGroups = readGroupSets.get(0); // first -I option must specify normal.bam
                        System.out.println(normalReadGroups.size() + " normal read groups");
                        //for ( String rg : normalReadGroups ) System.out.println("Normal RG: "+rg);

			tumorReadGroups = readGroupSets.get(1); // second -I option must specify tumor.bam
                        System.out.println(tumorReadGroups.size() + " tumor read groups");
                        // for ( String rg : tumorReadGroups ) System.out.println("Tumor RG: "+rg);
		} else {
			if ( nSams != 1 ) System.out.println("WARNING: multiple input files specified. \n"+
					"WARNING: Without --somatic option they will be merged and processed as a single sample");
		}

		try {
			output = new java.io.FileWriter(bed_file);
		} catch (IOException e) {
			throw new StingException("Failed to open file for writing BED output");
		}
	}


	@Override
	public Integer map(char[] ref, SAMRecord read) {

            if ( DEBUG ) {
                //            System.out.println("DEBUG>> read at "+ read.getAlignmentStart()+"-"+read.getAlignmentEnd()+
                //                    "("+read.getCigarString()+")");
                if ( read.getDuplicateReadFlag() ) System.out.println("DEBUG>> Duplicated read (IGNORED)");
            }

            if ( AlignmentUtils.isReadUnmapped(read) ||
			 read.getDuplicateReadFlag() ||
			 read.getNotPrimaryAlignmentFlag() ||
			 read.getMappingQuality() == 0 ) {
			return 0; // we do not need those reads!
            }

            if ( read.getReferenceIndex() != currentContigIndex ) {
                // we just jumped onto a new contig
                if ( DEBUG ) System.out.println("DEBUG>>> Moved to contig "+read.getReferenceName());
                if ( read.getReferenceIndex() < currentContigIndex ) // paranoidal
                    throw new StingException("Read "+read.getReadName()+": contig is out of order; input BAM file is unsorted");

                // print remaining indels from the previous contig (if any);
                if ( call_somatic ) emit_somatic(1000000000, true);
                else emit(1000000000,true);

                currentContigIndex = read.getReferenceIndex();
                currentPosition = read.getAlignmentStart();
                refName = new String(read.getReferenceName());

                location = GenomeLocParser.setContig(location,refName);

                normal_context.clear(); // reset coverage window; this will also set reference position to 0
                if ( call_somatic) tumor_context.clear();
            }

            // we have reset the window to the new contig if it was required and emitted everything we collected
            // on a previous contig. At this point we are guaranteed that we are set up properly for working
            // with the contig of the current read.

            // NOTE: all the sanity checks and error messages below use normal_context only. We make sure that normal_context and
            // tumor_context are synchronized exactly (windows are always shifted together by emit_somatic), so it's safe

            if ( read.getAlignmentStart() < currentPosition ) // oops, read out of order?
                throw new StingException("Read "+read.getReadName() +" out of order on the contig\n"+
                                         "Read starts at "+refName+":"+read.getAlignmentStart()+"; last read seen started at "+refName+":"+currentPosition
					+"\nLast read was: "+lastRead.getReadName()+" RG="+lastRead.getAttribute("RG")+" at "+lastRead.getAlignmentStart()+"-"
					+lastRead.getAlignmentEnd()+" cigar="+lastRead.getCigarString());

            currentPosition = read.getAlignmentStart();

            if ( read.getAlignmentStart() < normal_context.getStart() ) {
                // should never happen
                throw new StingException("Read "+read.getReadName()+": out of order on the contig\n"+
					"Read starts at "+read.getReferenceName()+":"+read.getAlignmentStart()+ " (cigar="+read.getCigarString()+
					"); window starts at "+normal_context.getStart());
            }

            lastRead = read;

            // a little trick here: we want to make sure that current read completely fits into the current
            // window so that we can accumulate indel observations over the whole length of the read.
            // The ::getAlignmentEnd() method returns the last position on the reference where bases from the
            // read actually match (M cigar elements). After our cleaning procedure, we can have reads that end
            // with I element, which is not gonna be counted into alignment length on the reference. On the other hand,
            // in this program we assign insertions, internally, to the first base *after* the insertion position.
            // Hence, we have to make sure that that extra base is already in the window or we will get IndexOutOfBounds.

            long alignmentEnd = read.getAlignmentEnd();
            Cigar c = read.getCigar();
            if ( c.getCigarElement(c.numCigarElements()-1).getOperator() == CigarOperator.I) alignmentEnd++;

            if ( alignmentEnd > normal_context.getStop()) {

                // we don't emit anything until we reach a read that does not fit into the current window.
                // At that point we try shifting the window to the start of that read (or reasonably close) and emit everything prior to
                // that position. This is legitimate, since the reads are sorted and  we are not gonna see any more coverage at positions
                // below the current read's start.
                // Clearly, we assume here that window is large enough to accomodate any single read, so simply shifting
                // the window to around the read's start will ensure that the read fits...

                if ( DEBUG) System.out.println("DEBUG>> Window at "+normal_context.getStart()+"-"+normal_context.getStop()+", read at "+
                                read.getAlignmentStart()+": trying to emit and shift" );
                if ( call_somatic ) emit_somatic( read.getAlignmentStart(), false );
                else emit( read.getAlignmentStart(), false );

                // let's double check now that the read fits after the shift
                if ( read.getAlignmentEnd() > normal_context.getStop()) {
                    // ooops, looks like the read does not fit into the window even after the latter was shifted!!
                    throw new StingException("Read "+read.getReadName()+": out of coverage window bounds. Probably window is too small.\n"+
                                             "Read length="+read.getReadLength()+"; cigar="+read.getCigarString()+"; start="+
                                             read.getAlignmentStart()+"; end="+read.getAlignmentEnd()+
                                             "; window start (after trying to accomodate the read)="+normal_context.getStart()+
					"; window end="+normal_context.getStop());
                }
            }

            if ( call_somatic ) {

                String rg = (String)read.getAttribute("RG");
                if ( rg == null ) throw new StingException("Read "+read.getReadName()+" has no read group in merged stream. RG is required for somatic calls.");

                if ( normalReadGroups.contains(rg) ) {
                    normal_context.add(read,ref);
                } else if ( tumorReadGroups.contains(rg) ) {
                    tumor_context.add(read,ref);
                } else {
                    throw new StingException("Unrecognized read group in merged stream: "+rg);
                }
            } else {
                normal_context.add(read, ref);
            }

            return 1;
	}


        /** Output indel calls up to the specified position and shift the window: after this method is executed, the                                     
         * first element of the window maps onto 'position', if possible, or at worst a few bases to the left of 'position' if we may need more 
         * reads to get full NQS-style statistics for an indel in the close proximity of 'position'.  
         *                                                                                                
         * @param position
         */
        private void emit(long position, boolean force) {

         }

        /** Takes the position, to which window shift is requested, and tries to adjust it in such a way that no NQS window is broken.
         * Namely, this method checks, iteratively, if there is an indel within NQS_WIDTH bases ahead of initially requested or adjusted 
         * shift position. If there is such an indel,
         * then shifting to that position would lose some or all NQS-window bases to the left of the indel (since it's not going to be emitted
         * just yet). Instead, this method tries to readjust the shift position leftwards so that full NQS window to the left of the next indel
         * is preserved. This method tries thie strategy 4 times (so that it would never walk away too far to the left), and if it fails to find
         * an appropriate adjusted shift position (which could happen if there are many indels following each other at short intervals), it will give up, 
         * go back to the original requested shift position and try finding the first shift poisition that has no indel associated with it.
         */

    private long adjustPosition(long request) {
        long initial_request = request;
        int attempts = 0;
        boolean failure = false;
        while ( tumor_context.hasIndelsInInterval(request,request+NQS_WIDTH) ||
              normal_context.hasIndelsInInterval(request,request+NQS_WIDTH) ) {
            request -= NQS_WIDTH;
            if ( DEBUG ) System.out.println("DEBUG>> indel observations present within "+NQS_WIDTH+" bases ahead. Resetting shift to "+request);
            attempts++;
            if ( attempts == 4 ) {
                failure = true;
                break;
            }
        }

        if ( failure ) {
            // we tried 4 times but did not find a good shift position that would preserve full nqs window
            // around all indels. let's fall back and find any shift position as long and there's no indel at the very
            // first position after the shift (this is bad for other reasons); if it breaks a nqs window, so be it
            request = initial_request;
            attempts = 0;
            while ( tumor_context.hasIndelsInInterval(request,request) ||
                  normal_context.hasIndelsInInterval(request,request) ) {
                request--;
                if ( DEBUG ) System.out.println("DEBUG>> indel observations present within "+NQS_WIDTH+" bases ahead. Resetting shift to "+request);
                attempts++;
                if ( attempts == 50 ) throw new StingException("Indel at every position in the interval ["+request+", "+initial_request+"]. Can not find a break to shift context window to");
            }
        }
        return request;
    }

    /** Output somatic indel calls up to the specified position and shift the coverage array(s): after this method is executed
     * first elements of the coverage arrays map onto 'position', or a few bases prior to the specified position
     * if there is an indel in close proximity to 'position' so that we may get more coverage around it later.
     *
     * @param position
     */
    private void emit_somatic(long position, boolean force) {

        position = adjustPosition(position);
        long move_to = position;

        for ( long pos = tumor_context.getStart() ; pos < Math.min(position,tumor_context.getStop()+1) ; pos++ ) {

            if ( tumor_context.indelsAt(pos).size() == 0 ) continue; // no indels in tumor

            IndelPrecall tumorCall = new IndelPrecall(tumor_context,pos,NQS_WIDTH);
            IndelPrecall normalCall = new IndelPrecall(normal_context,pos,NQS_WIDTH);

            if ( tumorCall.getCoverage() < minCoverage ) {
                if ( DEBUG ) {
                    System.out.println("DEBUG>> Indel in tumor at "+pos+"; coverare in tumor="+tumorCall.getCoverage()+" (SKIPPED)");
                }
                continue; // low coverage
            }
            if ( normalCall.getCoverage() < minNormalCoverage ) {
                if ( DEBUG ) {
                    System.out.println("DEBUG>> Indel in tumor at "+pos+"; coverare in normal="+normalCall.getCoverage()+" (SKIPPED)");
                }
                continue; // low coverage
            }

            if ( DEBUG ) System.out.println("DEBUG>> Indel in tumor at "+pos);

            long left = Math.max( pos-NQS_WIDTH, tumor_context.getStart() );
            long right = pos+tumorCall.getVariant().lengthOnRef()+NQS_WIDTH-1;

            if ( right >= position && ! force) {
                // we are not asked to force-shift, and there is more coverage around the current indel that we still need to collect

                // we are not asked to force-shift, and there's still additional coverage to the right of current indel, so its too early to emit it;
                // instead we shift only up to current indel pos - MISMATCH_WIDTH, so that we could keep collecting that coverage
                move_to = adjustPosition(left);
                if ( DEBUG ) System.out.println("DEBUG>> waiting for coverage; actual shift performed to "+ move_to);
                break;
            }

            if ( right > tumor_context.getStop() ) right = tumor_context.getStop(); // if indel is too close to the end of the window but we need to emit anyway (force-shift), adjust right

            location = GenomeLocParser.setStart(location,pos);
            location = GenomeLocParser.setStop(location,pos); // retrieve annotation data
            RODRecordList<rodRefSeq> annotationList = (refseqIterator == null ? null : refseqIterator.seekForward(location));

            if ( normalCall.failsNQSMismatch() ) {
                String fullRecord = makeFullRecord(normalCall,tumorCall);
                String annotationString = (refseqIterator == null ? "" : getAnnotationString(annotationList));
                out.println(fullRecord+
                "NORMAL_TOO_DIRTY\t"+annotationString);
                tumor_context.indelsAt(pos).clear();
                normal_context.indelsAt(pos).clear();
                    // we dealt with this indel; don't want to see it again
                    // (we might otherwise in the case when 1) there is another indel that follows
                    // within MISMATCH_WIDTH bases and 2) we'd need to wait for more coverage for that next indel)
                continue; // too dirty
            }
            if ( tumorCall.failsNQSMismatch() ) {
                String fullRecord = makeFullRecord(normalCall,tumorCall);
                String annotationString = (refseqIterator == null ? "" : getAnnotationString(annotationList));
                out.println(fullRecord+
                "TUMOR_TOO_DIRTY\t"+annotationString);
                tumor_context.indelsAt(pos).clear();
                normal_context.indelsAt(pos).clear();
                    // we dealt with this indel; don't want to see it again
                    // (we might otherwise in the case when 1) there is another indel that follows
                    // within MISMATCH_WIDTH bases and 2) we'd need to wait for more coverage for that next indel)
                continue; // too dirty
            }

            if ( tumorCall.isCall() ) {
                String message = tumorCall.makeBedLine(output);
                String annotationString = (refseqIterator == null ? "" : getAnnotationString(annotationList));

                StringBuilder fullRecord = new StringBuilder();
                fullRecord.append(makeFullRecord(normalCall,tumorCall));

                if ( normalCall.getVariant() == null ) {
                    fullRecord.append("SOMATIC");
                } else {
                    fullRecord.append("GERMLINE");
                }
                if ( verbose ) {
                    if ( refseqIterator == null ) out.println(fullRecord + "\t");
                    else out.println(fullRecord + "\t"+ annotationString);
                }
            }

            tumor_context.indelsAt(pos).clear();
            normal_context.indelsAt(pos).clear();
                // we dealt with this indel; don't want to see it again
                // (we might otherwise in the case when 1) there is another indel that follows
                // within MISMATCH_WIDTH bases and 2) we'd need to wait for more coverage for that next indel)

//			for ( IndelVariant var : variants ) {
//				System.out.print("\t"+var.getType()+"\t"+var.getBases()+"\t"+var.getCount());
//			}
        }

        if ( DEBUG ) System.out.println("DEBUG>> Actual shift to " + move_to + " ("+position+")");
        tumor_context.shift((int)(move_to - tumor_context.getStart() ) );
        normal_context.shift((int)(move_to - normal_context.getStart() ) );
    }

    private String makeFullRecord(IndelPrecall normalCall, IndelPrecall tumorCall) {
        StringBuilder fullRecord = new StringBuilder();
        fullRecord.append(tumorCall.makeEventString());
        fullRecord.append('\t');
        fullRecord.append(normalCall.makeStatsString("N_"));
        fullRecord.append('\t');
        fullRecord.append(tumorCall.makeStatsString("T_"));
        fullRecord.append('\t');
        return fullRecord.toString();
    }

    private String getAnnotationString(RODRecordList<rodRefSeq> ann) {
        if ( ann == null ) return annGenomic;
        else {
            StringBuilder b = new StringBuilder();

            if ( rodRefSeq.isExon(ann) ) {
                if ( rodRefSeq.isCodingExon(ann) ) b.append(annCoding); // both exon and coding = coding exon sequence
                else b.append(annUTR); // exon but not coding = UTR
            } else {
                if ( rodRefSeq.isCoding(ann) ) b.append(annIntron); // not in exon, but within the coding region = intron
                else b.append(annUnknown); // we have no idea what this is. this may actually happen when we have a fully non-coding exon...
            }
            b.append('\t');
            b.append(((Transcript)ann.getRecords().get(0)).getGeneName()); // there is at least one transcript in the list, guaranteed
//			while ( it.hasNext() ) { //
//				t.getGeneName()
//			}
            return b.toString();
        }

    }

    @Override
    public void onTraversalDone(Integer result) {
        if ( call_somatic ) emit_somatic(1000000000, true);
        else emit(1000000000,true); // emit everything we might have left
        try {
            output.close();
        } catch (IOException e) {
            System.out.println("Failed to close output BED file gracefully, data may be lost");
            e.printStackTrace();
        }
        super.onTraversalDone(result);
    }

    @Override
    public Integer reduce(Integer value, Integer sum) {
        if ( value == -1 ) {
            onTraversalDone(sum);
            System.exit(1);
        }
        sum += value;
        return sum;
    }

    @Override
    public Integer reduceInit() {
        return new Integer(0);
    }


        static class IndelVariant {
            public static enum Type { I, D};
            private String bases;
            private Type type;

            private Set<SAMRecord> reads = new HashSet<SAMRecord>(); // keep track of reads that have this indel
            private Set<String> samples = new HashSet<String>();   // which samples had the indel described by this object

            public IndelVariant(SAMRecord read , Type type, String bases) {
                this.type = type;
                this.bases = bases.toUpperCase();
                addObservation(read);
            }

            /** Adds another observation for the current indel. It is assumed that the read being registered
             * does contain the observation, no checks are performed. Read's sample is added to the list of samples
             * this indel was observed in as well.
             * @param read
             */
            public void addObservation(SAMRecord read) {
                if ( reads.contains(read) ) throw new StingException("Attempting to add indel observation that was already registered");
                reads.add(read);
                String sample = null;
                if ( read.getReadGroup() != null ) sample = read.getReadGroup().getSample();
                if ( sample != null ) samples.add(sample);
            }


            /** Returns length of the event on the reference (number of deleted bases
             * for deletions, -1 for insertions.
             * @return
             */
            public int lengthOnRef() {
                if ( type == Type.D ) return bases.length();
                else return 0;
            }


            public void addSample(String sample) {
                if ( sample != null )
                samples.add(sample);
            }

            public String getSamples() {
                StringBuffer sb = new StringBuffer();
                Iterator<String> i = samples.iterator();
                while ( i.hasNext() ) {
                    sb.append(i.next());
                    if ( i.hasNext() )
                        sb.append(",");
                }
                return sb.toString();
            }

            public Set<SAMRecord> getReadSet() { return reads; }

            public int getCount() { return reads.size(); }

            public String getBases() { return bases; }

            public Type getType() { return type; }

            @Override
            public boolean equals(Object o) {
                if ( ! ( o instanceof IndelVariant ) ) return false;
                IndelVariant that = (IndelVariant)o;
                return ( this.type == that.type && this.bases.equals(that.bases) );
            }

            public boolean equals(Type type, String bases) {
                return ( this.type == type && this.bases.equals(bases.toUpperCase()) );
            }
        }

    /**
     * Utility class that encapsulates the logic related to collecting all the stats and counts required to
     * make (or discard) a call, as well as the calling heuristics that uses those data.
      */
    class IndelPrecall {
//        private boolean DEBUG = false;
        private int NQS_MISMATCH_CUTOFF = 1000000;
        private double AV_MISMATCHES_PER_READ = 1.5;

        private int nqs = 0;
        private IndelVariant consensus_indel = null; // indel we are going to call
        private long pos = -1 ; // position on the ref
        private int total_coverage = 0; // total number of reads overlapping with the event
        private int consensus_indel_count = 0; // number of reads, in which consensus indel was observed
        private int all_indel_count = 0 ; // number of reads, in which any indel was observed at current position

        private int total_mismatches_in_nqs_window = 0; // total number of mismatches in the nqs window around the indel
        private int total_bases_in_nqs_window = 0; // total number of bases in the nqs window (some reads may not fully span the window so it's not coverage*nqs_size)
        private int total_base_qual_in_nqs_window = 0; // sum of qualitites of all the bases in the nqs window
        private int total_mismatching_base_qual_in_nqs_window = 0; // sum of qualitites of all mismatching bases in the nqs window

        private int indel_read_mismatches_in_nqs_window = 0;   // mismatches inside the nqs window in indel-containing reads only
        private int indel_read_bases_in_nqs_window = 0;  // number of bases in the nqs window from indel-containing reads only
        private int indel_read_base_qual_in_nqs_window = 0; // sum of qualitites of bases in nqs window from indel-containing reads only
        private int indel_read_mismatching_base_qual_in_nqs_window = 0; // sum of qualitites of mismatching bases in the nqs window from indel-containing reads only


        private int consensus_indel_read_mismatches_in_nqs_window = 0; // mismatches within the nqs window from consensus indel reads only
        private int consensus_indel_read_bases_in_nqs_window = 0;  // number of bases in the nqs window from consensus indel-containing reads only
        private int consensus_indel_read_base_qual_in_nqs_window = 0; // sum of qualitites of bases in nqs window from consensus indel-containing reads only
        private int consensus_indel_read_mismatching_base_qual_in_nqs_window = 0; // sum of qualitites of mismatching bases in the nqs window from consensus indel-containing reads only


        private double consensus_indel_read_total_mm = 0.0; // sum of all mismatches in reads that contain consensus indel
        private double all_indel_read_total_mm = 0.0; // sum of all mismatches in reads that contain any indel at given position
        private double all_read_total_mm = 0.0; // sum of all mismatches in all reads

        private double consensus_indel_read_total_mapq = 0.0; // sum of mapping qualitites of all reads with consensus indel
        private double all_indel_read_total_mapq = 0.0 ; // sum of mapping qualitites of all reads with (any) indel at current position
        private double all_read_total_mapq = 0.0; // sum of all mapping qualities of all reads

        private PrimitivePair.Int consensus_indel_read_orientation_cnt = new PrimitivePair.Int();
        private PrimitivePair.Int all_indel_read_orientation_cnt = new PrimitivePair.Int();
        private PrimitivePair.Int all_read_orientation_cnt = new PrimitivePair.Int();

        public IndelPrecall(WindowContext context, long position, int nqs_width) {
            this.pos = position;
            this.nqs = nqs_width;
            total_coverage = context.coverageAt(pos);
            List<IndelVariant> variants = context.indelsAt(pos);
            findConsensus(variants);

            // pos is the first base after the event: first deleted base or first base after insertion.
            // hence, [pos-nqs, pos+nqs-1] (inclusive) is the window with nqs bases on each side of a no-event or an insertion
            // and [pos-nqs, pos+Ndeleted+nqs-1] is the window with nqs bases on each side of a deletion.
            // we initialize the nqs window for no-event/insertion case
            long left = Math.max( pos-nqs, context.getStart() );
            long right = Math.min(pos+nqs-1, context.getStop());
//if ( pos == 3534096 ) System.out.println("pos="+pos +" total reads: "+context.getReads().size());
            Iterator<SAMRecord> read_iter = context.getReads().iterator();
            Iterator<byte[]> flag_iter = context.getMMFlags().iterator();
            Iterator<Integer> mm_iter = context.getTotalMMs().iterator();
            Iterator <byte[]> qual_iter = context.getExpandedQuals().iterator();

            while ( read_iter.hasNext() ) {
                SAMRecord read = read_iter.next();
                byte[] flags = flag_iter.next();
                byte[] quals = qual_iter.next();
                Integer mm = mm_iter.next();

                if( read.getAlignmentStart() > pos || read.getAlignmentEnd() < pos ) continue;

                long local_right = right; // end of nqs window for this particular read. May need to be advanced further right
                                          // if read has a deletion. The gap in the middle of nqs window will be skipped
                                          // automatically since flags/quals are set to -1 there

                boolean read_has_a_variant = false;
                boolean read_has_consensus = ( consensus_indel!= null && consensus_indel.getReadSet().contains(read) );
                for ( IndelVariant v : variants ) {
                    if ( v.getReadSet().contains(read) ) {
                        read_has_a_variant = true;
                        local_right += v.lengthOnRef();
                        break;
                    }
                }

                if ( read_has_consensus ) {
                    consensus_indel_read_total_mm += mm.intValue();
                    consensus_indel_read_total_mapq += read.getMappingQuality();
                    if ( read.getReadNegativeStrandFlag() ) consensus_indel_read_orientation_cnt.second++;
                    else consensus_indel_read_orientation_cnt.first++;
                }
                if ( read_has_a_variant ) {
                    all_indel_read_total_mm += mm.intValue();
                    all_indel_read_total_mapq += read.getMappingQuality();
                    if ( read.getReadNegativeStrandFlag() ) all_indel_read_orientation_cnt.second++;
                    else all_indel_read_orientation_cnt.first++;
                }

                all_read_total_mm+= mm.intValue();
                all_read_total_mapq += read.getMappingQuality();
                if ( read.getReadNegativeStrandFlag() ) all_read_orientation_cnt.second++;
                else all_read_orientation_cnt.first++;

                for ( int pos_in_flags = Math.max((int)(left - read.getAlignmentStart()),0);
                      pos_in_flags <= Math.min((int)local_right-read.getAlignmentStart(),flags.length - 1);
                       pos_in_flags++) {

                        if ( flags[pos_in_flags] == -1 ) continue; // gap (deletion), skip it; we count only bases aligned to the ref
                        total_bases_in_nqs_window++;
                        if ( read_has_consensus ) consensus_indel_read_bases_in_nqs_window++;
                        if ( read_has_a_variant ) indel_read_bases_in_nqs_window++;

                        if ( quals[pos_in_flags] != -1 ) {

                            total_base_qual_in_nqs_window += quals[pos_in_flags];
                            if ( read_has_a_variant ) indel_read_base_qual_in_nqs_window += quals[pos_in_flags];
                            if ( read_has_consensus ) consensus_indel_read_base_qual_in_nqs_window += quals[pos_in_flags];
                        }

                        if ( flags[pos_in_flags] == 1 ) { // it's a mismatch
                            total_mismatches_in_nqs_window++;
                            total_mismatching_base_qual_in_nqs_window += quals[pos_in_flags];

                            if ( read_has_consensus ) {
                                consensus_indel_read_mismatches_in_nqs_window++;
                                consensus_indel_read_mismatching_base_qual_in_nqs_window += quals[pos_in_flags];
                            }
                            
                            if ( read_has_a_variant ) {
                                indel_read_mismatches_in_nqs_window++;
                                indel_read_mismatching_base_qual_in_nqs_window += quals[pos_in_flags];
                            }
                        }
                }
//         if ( pos == 3534096 ) {
//             System.out.println(read.getReadName());
//             System.out.println(" cons nqs bases="+consensus_indel_read_bases_in_nqs_window);
//             System.out.println(" qual sum="+consensus_indel_read_base_qual_in_nqs_window);
//         }

            }
        }

        public long getPosition() { return pos; }

        public boolean hasObservation() { return consensus_indel != null; }

        public int getCoverage() { return total_coverage; }

        public double getTotalMismatches() { return all_read_total_mm; }
        public double getConsensusMismatches() { return consensus_indel_read_total_mm; }
        public double getAllVariantMismatches() { return all_indel_read_total_mm; }

        /** Returns average number of mismatches per consensus indel-containing read */
        public double getAvConsensusMismatches() {
            return ( consensus_indel_count != 0 ? consensus_indel_read_total_mm/consensus_indel_count : 0.0 );
        }

        /** Returns average number of mismatches per read across all reads matching the ref (not containing any indel variants) */
        public double getAvRefMismatches() {
            int coverage_ref = total_coverage-all_indel_count;
            return ( coverage_ref != 0 ? (all_read_total_mm - all_indel_read_total_mm )/coverage_ref : 0.0 );
        }

        public PrimitivePair.Int getConsensusStrandCounts() {
            return consensus_indel_read_orientation_cnt;
        }

        public PrimitivePair.Int getRefStrandCounts() {
            return new PrimitivePair.Int(all_read_orientation_cnt.first-all_indel_read_orientation_cnt.first,
                                         all_read_orientation_cnt.second - all_indel_read_orientation_cnt.second);
        }

        /** Returns a sum of mapping qualities of all reads spanning the event. */
        public double getTotalMapq() { return all_read_total_mapq; }

        /** Returns a sum of mapping qualities of all reads, in which the consensus variant is observed. */
        public double getConsensusMapq() { return consensus_indel_read_total_mapq; }

        /** Returns a sum of mapping qualities of all reads, in which any variant is observed at the current event site. */
        public double getAllVariantMapq() { return all_indel_read_total_mapq; }

        /** Returns average mapping quality per consensus indel-containing read. */
        public double getAvConsensusMapq() {
            return ( consensus_indel_count != 0 ? consensus_indel_read_total_mapq/consensus_indel_count : 0.0 );
        }

        /** Returns average number of mismatches per read across all reads matching the ref (not containing any indel variants). */
        public double getAvRefMapq() {
            int coverage_ref = total_coverage-all_indel_count;
            return ( coverage_ref != 0 ? (all_read_total_mapq - all_indel_read_total_mapq )/coverage_ref : 0.0 );
        }

        /** Returns fraction of bases in NQS window around the indel that are mismatches, across all reads,
         * in which consensus indel is observed. NOTE: NQS window for indel containing reads is defined around
         * the indel itself (e.g. for a 10-base deletion spanning [X,X+9], the 5-NQS window is {[X-5,X-1],[X+10,X+15]}
         * */
        public double getNQSConsensusMMRate() {
            if ( consensus_indel_read_bases_in_nqs_window == 0 ) return 0;
            return ((double)consensus_indel_read_mismatches_in_nqs_window)/consensus_indel_read_bases_in_nqs_window;
        }

        /** Returns fraction of bases in NQS window around the indel start position that are mismatches, across all reads
         * that align to the ref (i.e. contain no indel observation at the current position). NOTE: NQS window for ref
         * reads is defined around the event start position, NOT around the actual consensus indel.
         * */
        public double getNQSRefMMRate() {
            int num_ref_bases = total_bases_in_nqs_window - indel_read_bases_in_nqs_window;
            if ( num_ref_bases == 0 ) return 0;
            return ((double)(total_mismatches_in_nqs_window - indel_read_mismatches_in_nqs_window))/num_ref_bases;
        }

        /** Returns average base quality in NQS window around the indel, across all reads,
         * in which consensus indel is observed. NOTE: NQS window for indel containing reads is defined around
         * the indel itself (e.g. for a 10-base deletion spanning [X,X+9], the 5-NQS window is {[X-5,X-1],[X+10,X+15]}
         * */
        public double getNQSConsensusAvQual() {
            if ( consensus_indel_read_bases_in_nqs_window == 0 ) return 0;
            return ((double)consensus_indel_read_base_qual_in_nqs_window)/consensus_indel_read_bases_in_nqs_window;
        }

        /** Returns fraction of bases in NQS window around the indel start position that are mismatches, across all reads
         * that align to the ref (i.e. contain no indel observation at the current position). NOTE: NQS window for ref
         * reads is defined around the event start position, NOT around the actual consensus indel.
         * */
        public double getNQSRefAvQual() {
            int num_ref_bases = total_bases_in_nqs_window - indel_read_bases_in_nqs_window;
            if ( num_ref_bases == 0 ) return 0;
            return ((double)(total_base_qual_in_nqs_window - indel_read_base_qual_in_nqs_window))/num_ref_bases;
        }

        public int getTotalNQSMismatches() { return total_mismatches_in_nqs_window; }

        public int getAllVariantCount() { return all_indel_count; }
        public int getConsensusVariantCount() { return consensus_indel_count; }

        public boolean failsNQSMismatch() {
            //TODO wrong fraction: mismatches are counted only in indel-containing reads, but total_coverage is used!
            return ( indel_read_mismatches_in_nqs_window > NQS_MISMATCH_CUTOFF ) ||
                    ( indel_read_mismatches_in_nqs_window > total_coverage * AV_MISMATCHES_PER_READ );
        }

        public IndelVariant getVariant() { return consensus_indel; }

        public boolean isCall() {
            boolean ret = ! failsNQSMismatch() && ( consensus_indel_count >= minIndelCount &&
                    (double)consensus_indel_count > minFraction * total_coverage &&
                    (double) consensus_indel_count > minConsensusFraction*all_indel_count );
            if ( DEBUG && ! ret ) System.out.println("DEBUG>>  NOT a call: count="+consensus_indel_count+
                        " total_count="+all_indel_count+" cov="+total_coverage+
                " minConsensusF="+((double)consensus_indel_count)/all_indel_count+
                    " minF="+((double)consensus_indel_count)/total_coverage);
            return ret;

        }

        /** Utility method: finds the indel variant with the largest count (ie consensus) among all the observed
         * variants, and sets the counts of consensus observations and all observations of any indels (including non-consensus)
         * @param variants
         * @return
         */
        private void findConsensus(List<IndelVariant> variants) {
            for ( IndelVariant var : variants ) {
                if ( DEBUG ) System.out.println("DEBUG>> Variant "+var.getBases()+" (cnt="+var.getCount()+")");
                int cnt = var.getCount();
                all_indel_count +=cnt;
                if ( cnt > consensus_indel_count ) {
                    consensus_indel = var;
                    consensus_indel_count = cnt;
                }
            }
            if ( DEBUG && consensus_indel != null ) System.out.println("DEBUG>> Returning: "+consensus_indel.getBases()+
                    " (cnt="+consensus_indel.getCount()+") with total count of "+all_indel_count);
        }


        public String makeBedLine(java.io.Writer bedOutput) {
            int event_length = consensus_indel.lengthOnRef();
            if ( event_length < 0 ) event_length = 0;
            StringBuffer message = new StringBuffer();
            message.append(refName+"\t"+(pos-1)+"\t");
            if ( FORMAT_1KG )
                message.append(consensus_indel.getBases().length() + "\t" + (event_length > 0 ? "D" : "I") + "\t" +
                        consensus_indel.getBases() + "\t" + consensus_indel.getSamples());
            else
                message.append((pos-1+event_length)+"\t"+(event_length>0? "-":"+")+consensus_indel.getBases() +":"+all_indel_count+"/"+total_coverage);

            if ( bedOutput != null ) {
                try {
                    bedOutput.write(message.toString()+"\n");
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                    e.printStackTrace();
                    throw new StingException("Error encountered while writing into output BED file");
                }
            }
            return message.toString();
        }

        public String makeEventString() {
            int event_length = consensus_indel.lengthOnRef();
            if ( event_length < 0 ) event_length = 0;
            StringBuffer message = new StringBuffer();
            message.append(refName);
            message.append('\t');
            message.append(pos-1);
            message.append('\t');
            message.append(pos-1+event_length);
            message.append('\t');
            message.append((event_length>0?'-':'+'));
            message.append(consensus_indel.getBases());
            return message.toString();
        }

        public String makeStatsString(String prefix) {
             StringBuilder message = new StringBuilder();
             message.append(prefix+"OBS_COUNTS[C/A/R]:"+getConsensusVariantCount()+"/"+getAllVariantCount()+"/"+getCoverage());
             message.append('\t');
             message.append(prefix+"AV_MM[C/R]:"+String.format("%.2f/%.2f",getAvConsensusMismatches(),
                                 getAvRefMismatches()));
             message.append('\t');
             message.append(prefix+"AV_MAPQ[C/R]:"+String.format("%.2f/%.2f",getAvConsensusMapq(),
                                getAvRefMapq()));
             message.append('\t');
             message.append(prefix+"NQS_MM_RATE[C/R]:"+String.format("%.2f/%.2f",getNQSConsensusMMRate(),getNQSRefMMRate()));
             message.append('\t');
             message.append(prefix+"NQS_AV_QUAL[C/R]:"+String.format("%.2f/%.2f",getNQSConsensusAvQual(),getNQSRefAvQual()));

             PrimitivePair.Int strand_cons = getConsensusStrandCounts();
             PrimitivePair.Int strand_ref = getRefStrandCounts();
             message.append('\t');
             message.append(prefix+"STRAND_COUNTS[C/C/R/R]:"+strand_cons.first+"/"+strand_cons.second+"/"+strand_ref.first+"/"+strand_ref.second);
             return message.toString();
         }

    }

    class WindowContext {
            private List<SAMRecord> reads;
            private List<byte[]> mismatch_flags;
            private List<byte[]> expanded_quals;
            private List<Integer> mms;
            private long start=0; // where the window starts on the ref, 1-based
            private CircularArray< List< IndelVariant > > indels;

            private List<IndelVariant> emptyIndelList = new ArrayList<IndelVariant>();


            public WindowContext(long start, int length) {
                this.start = start;
                indels = new CircularArray< List<IndelVariant> >(length);
                reads = new LinkedList<SAMRecord>();
                mismatch_flags = new LinkedList<byte[]>();
 //           offsets = new LinkedList<Integer>();
                mms = new LinkedList<Integer>();
                expanded_quals = new LinkedList<byte[]>();
            }

            /** Returns 1-based reference start position of the interval this object keeps context for.
             *
             * @return
             */
            public long getStart() { return start; }

            /** Returns 1-based reference stop position (inclusive) of the interval this object keeps context for.
             *
             * @return
             */
            public long getStop() { return start + indels.length() - 1; }

            /** Resets reference start position to 0 and clears the context.
             *
             */
            public void clear() {
                start = 0;
                mms.clear();
                reads.clear();
                mismatch_flags.clear();
                expanded_quals.clear();
                indels.clear();
            }

        /**
         * Returns true if any indel observations are present in the specified interval
         * [begin,end] (1-based, inclusive). Interval can be partially of fully outside of the
         * current context window: positions outside of the window will be ignored.
         * @param begin
         * @param end
         */
            public boolean hasIndelsInInterval(long begin, long end) {
                for ( long k = Math.max(start,begin); k < Math.min(getStop(),end); k++ ) {
                    if ( indelsAt(k) != emptyIndelList ) return true;
                }
                return false;               
            }

            public List<SAMRecord> getReads() { return reads; }
            public List<byte[]> getMMFlags() { return mismatch_flags; }
            public List<Integer> getTotalMMs() { return mms; }
            public List<byte[]> getExpandedQuals() { return expanded_quals; }

            /** Returns the number of reads spanning over the specified reference position                                                                                                       
             * (regardless of whether they have a base or indel at that specific location)                                                                                                       
             * @param refPos position on the reference; must be within the bounds of the window     
             */
            public int coverageAt(final long refPos) {
                int cov = 0;
                for ( SAMRecord read : reads ) {
                    if ( read.getAlignmentStart() > refPos || read.getAlignmentEnd() < refPos ) continue;
                    cov++;
                } 
                return cov;
            }


            /** Shifts current window to the right along the reference contig by the specified number of bases.
             * The context will be updated accordingly (indels and reads that go out of scope will be dropped).
             * @param offset
             */
            public void shift(int offset) {
                start += offset;

                indels.shiftData(offset);
                if ( indels.get(0) != null && indels.get(0).size() != 0 ) {
                    IndelVariant indel =  indels.get(0).get(0);

                    throw new StingException("Indel found at the first position ("+start+") after a shift was performed: currently not supported: "+
                    (indel.getType()==IndelVariant.Type.I?"+":"-")+indel.getBases()+"; reads: "+indel.getReadSet().iterator().next().getReadName());
                }
                
                Iterator<SAMRecord> read_iter = reads.iterator();
                Iterator<Integer> mm_iter = mms.iterator();
                Iterator<byte[]> flags_iter = mismatch_flags.iterator();
                Iterator<byte[]> quals_iter = expanded_quals.iterator();

                while ( read_iter.hasNext() ) {
                    SAMRecord r = read_iter.next();
                    mm_iter.next();
                    flags_iter.next();
                    quals_iter.next();

                    if ( r.getAlignmentEnd() < start ) { // discard reads and associated data that went out of scope
                        read_iter.remove();
                        mm_iter.remove();
                        flags_iter.remove();
                        quals_iter.remove();
                    }
                }
            }

            public void add(SAMRecord read, char [] ref) {

                final long rStart = read.getAlignmentStart();
                final long rStop = read.getAlignmentEnd();
                final String readBases = read.getReadString().toUpperCase();

                byte flags[] = new byte[(int)(rStop-rStart+1)];
                byte quals[] = new byte[(int)(rStop-rStart+1)];

                int localStart = (int)( rStart - start ); // start of the alignment wrt start of the current window, 0-based

                // now let's extract indels:

                Cigar c = read.getCigar();
                final int nCigarElems = c.numCigarElements();

                int posOnRead = 0;
                int posOnRef = 0; // the chunk of reference ref[] that we have access to is aligned with the read:
                                  // its start on the actual full reference contig is r.getAlignmentStart()
                int mm=0; // number of single-base mismatches in the current read (indels do not count!)

                for ( int i = 0 ; i < nCigarElems ; i++ ) {

                    final CigarElement ce = c.getCigarElement(i);
                    IndelVariant.Type type = null;
                    String bases = null;
                    int eventPosition = posOnRef;
	            	            
                    switch(ce.getOperator()) {
                    case I:
                        type = IndelVariant.Type.I;
                        bases = readBases.substring(posOnRead,posOnRead+ce.getLength());
                        // will increment position on the read below, there's no 'break' statement yet...
                    case H:
                    case S:
                        // here we also skip hard and soft-clipped bases on the read; according to SAM format specification,
                        // alignment start position on the reference points to where the actually aligned
                        // (not clipped) bases go, so we do not need to increment reference position here
                        posOnRead += ce.getLength();
                        break;
                    case D:
                        type = IndelVariant.Type.D;
                        bases = new String( ref, posOnRef, ce.getLength() );
                        for( int k = 0 ; k < ce.getLength(); k++, posOnRef++ ) flags[posOnRef] = quals[posOnRef] = -1;

                        break;
                    case M: 
                        for ( int k = 0; k < ce.getLength(); k++, posOnRef++, posOnRead++ ) {
                            if ( readBases.charAt(posOnRead) != //note: readBases was uppercased above!
                                 Character.toUpperCase(ref[posOnRef]) ) { // mismatch!
                                mm++;
                                flags[posOnRef] = 1;
                            }
                            quals[posOnRef] = read.getBaseQualities()[posOnRead];
                        }
                        break; // advance along the gapless block in the alignment
                    default :
                        throw new IllegalArgumentException("Unexpected operator in cigar string: "+ce.getOperator());
                    }

                    if ( type == null ) continue; // element was not an indel, go grab next element...

                    // we got an indel if we are here...
                    if ( i == 0 ) logger.debug("Indel at the start of the read "+read.getReadName());
                    if ( i == nCigarElems - 1) logger.debug("Indel at the end of the read "+read.getReadName());
                
                    // note that here we will be assigning indels to the first deleted base or to the first
                    // base after insertion, not to the last base before the event!
                    addIndelObservation(localStart+eventPosition, type, bases, read);
                }
                reads.add(read);
                mms.add(mm);
                mismatch_flags.add(flags);
                expanded_quals.add(quals);
                //                offsets.add(localStart);
            }

            private void addIndelObservation(int pos, IndelVariant.Type type, String bases, SAMRecord r) {
                List<IndelVariant> indelsAtSite;
                try {
                    indelsAtSite = indels.get(pos);
                } catch (IndexOutOfBoundsException e) {
                    System.out.println("Read "+r.getReadName()+": out of coverage window bounds.Probably window is too small.\n"+
                        "Read length="+r.getReadLength()+"; cigar="+r.getCigarString()+"; start="+
                        r.getAlignmentStart()+"; end="+r.getAlignmentEnd()+"; window start="+getStart()+
                        "; window end="+getStop());
                    throw e;
                }

                if ( indelsAtSite == null ) {
                    indelsAtSite = new ArrayList<IndelVariant>();
                    indels.set(pos, indelsAtSite);
                }

                boolean found = false;
                for ( IndelVariant v : indelsAtSite ) {
                    if ( ! v.equals(type, bases) ) continue;

                    v.addObservation(r);
                    found = true;
                    break;
                }
                
                if ( ! found ) {
                    IndelVariant v = new IndelVariant(r, type, bases);
                    indelsAtSite.add(v);
                }
            }

            public List<IndelVariant> indelsAt( final long refPos ) {
                List<IndelVariant> l = indels.get((int)( refPos - start ));
                if ( l == null ) return emptyIndelList;
                else return l;
            }


        }

}
