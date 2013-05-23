#!/usr/bin/perl -w
use strict;

# For documentation, see usage() method, or run with "-h" option

my %selection = ();  # subset of ontologies to run on (defaults to all)
my $dry_run = 0;     # do not deploy if dry run is set
my $target_dir = './deployed-ontologies';  # in a production setting, this would be a path to web-visible area, e.g. berkeley CDN or NFS

while ($ARGV[0] && $ARGV[0] =~ /^\-/) {
    my $opt = shift @ARGV;
    if ($opt eq '-h' || $opt eq '--help') {
        print &usage();
    }
    elsif ($opt eq '-s' || '--select') {
        $selection{shift @ARGV} = 1;
    }
    elsif ($opt eq '-t' || '--target-dir') {
        $target_dir = shift @ARGV;
    }
    elsif ($opt eq '-d' || '--dry-run') {
        $dry_run = 1;
    }
    else {
        die "unknown option: $opt";
    }
}

# Build-in registry
my %ont_info = get_ont_info();

# set up dir structure if not present
if (!(-d 'src')) {
    run("mkdir src");
}
if (!(-d $target_dir)) {
    run("mkdir $target_dir");
}

# --GLOBALS--
my $ont;  # current ontology. Always an ontology ID such as 'go', cl', ...
my $n_errs = 0;   # total errs found
my @errs = ();    # err objects
my @onts_to_deploy = ();   # ont IDs that were successful
my @failed_onts = ();   # ont IDs that fail
my @failed_infallible_onts = ();   # ont IDs that fail that cause an error

# --MAIN--
# Iterate over all ontologies attempting to build or mirror
foreach my $k (keys %ont_info) {
    $ont = $k;

    if (keys %selection) {
        next unless $selection{$ont};
    }
    debug("ONTOLOGY: $ont");

    my $info = $ont_info{$ont};
    my $method = lc($info->{method});
    my $source_url = $info->{source_url};

    my $success = 0;

    # Method: vcs -- Version Control System - mirror package directly from svn/git checkout/update
    if ($method eq 'vcs') {

        # we always checkout into a staging dir
        my $dir = "stage-$ont";

        if (-d $dir) {
            # already checked out - issue update
            my $cmd = $info->{update};
            my $system = $info->{system};
            if (!$cmd) {
                if ($system eq 'svn') {
                    $cmd = 'svn --ignore-externals update';
                }
                elsif ($system eq 'git') {
                    $cmd = 'git pull';
                }
                else {
                    die "$system not known";
                }
            }
            $success = run("cd $dir && $cmd");
        }
        else {
            # initial checkout
            my $cmd = $info->{checkout};
            if ($cmd) {
                $success = run("$cmd $dir");
            }
            else {
                $success = 0;
                debug("Config error: checkout not set for $ont");
            }
        }

        # allow optional subdir. E.g. if we check out to project root, we may want to copy from src/ontology to target
        my $srcdir = $dir;
        if ($info->{path}) {
            $srcdir .= "/".$info->{path};
        }
        # TODO - custom post-commands

        # copy from staging checkout area to target
        if ($success) {
            $success = run("rsync -avz --delete $srcdir/* $ont/");
        }
        else {
            debug("will not rsync to target as previous steps were not successful");
        }
    }

    # Method: obo2owl -- Build entire package from single obo file using OORT
    if ($method eq 'obo2owl') {
        my $SRC = "src/$ont.obo";
        my @OORT_ARGS = "--reasoner elk";
        if ($info->{oort_args}) {
            @OORT_ARGS = $info->{oort_args};
        }
        my $env = '';
        if ($info->{oort_memory}) {
            $env = "OORT_MEMORY=$info->{oort_memory} ";
        }
        $success = run("wget --no-check-certificate $source_url -O $SRC");
        if ($success) {
            # Oort places package files directly in target area, if successful
            $success = run($env."ontology-release-runner --skip-release-folder --skip-format owx --allow-overwrite --outdir $ont @OORT_ARGS --asserted --simple $SRC");
        }
        else {
            debug("will not run Oort as wget was unsuccessful");
        }
    }

    # Method: owl2obo -- Build entire package from single obo file using OORT
    if ($method eq 'owl2obo') {

        # TODO - reuse code with obo2owl. Keep separate for now, as owl2obo may require extra configuration
        my $SRC = "src/$ont.owl";
        my @OORT_ARGS = "--reasoner elk";
        if ($info->{oort_args}) {
            @OORT_ARGS = $info->{oort_args};
        }
        $success = run("wget --no-check-certificate $source_url -O $SRC");
        # TODO - less strict mode for owl2obo, many ontologies do not conform to obo constraints
        # TODO - allow options including translation of annotation axioms, merging of import closure, etc
        if ($success) {
            # Oort places package files directly in target area, if successful
            $success = run("ontology-release-runner --repair-cardinality --skip-release-folder --skip-format owx --allow-overwrite --outdir $ont @OORT_ARGS --asserted --simple $SRC");
        }
        else {
            debug("will not run Oort as wget was unsuccessful");
        }
    }

    # Method: archive -- Mirror package from archive
    if ($method eq 'archive') {
        my $SRC = "src/$ont-archive.zip";
        my $path = $info->{path};
        if (!$path) {
            die "must set path for $ont";
        }
        $success = run("wget --no-check-certificate $source_url -O $SRC");
        if ($success) {
            $success = run("unzip -o $SRC");
            if ($success) {
                $success = run("rsync -avz --delete $path/* $ont/");
            }
            else {
                debug("unzip failed for $ont");
            }
        }
        else {
            debug("wget failed on $source_url - no further action taken on $ont");
        }
    }

    if ($method eq 'custom') {
        die "not implemented";
    }

    if ($success) {
        debug("Slated for deployment: $ont");
        push(@onts_to_deploy, $ont);
    }
    else {
        push(@failed_onts, $ont);
        if ($info->{infallible}) {
            push(@failed_infallible_onts, $ont);
        }
    }
}

# --REPORTING--
print "Build completed\n";
print "N_Errors: $n_errs\n";
foreach my $err (@errs) {
    print "ERROR: $err->{ont} $err->{cmd} $err->{err_text}\n";
}
printf "# Failed ontologies: %d\n", scalar(@failed_onts);
foreach my $font (@failed_onts) {
    print "FAIL: $font\n";
}
my $errcode = 0;
if (@failed_infallible_onts) {
    printf "# Failed ontologies: %d\n", scalar(@failed_onts);
    foreach my $font (@failed_infallible_onts) {
        print "FAIL: $font # THIS SHOULD NOT FAIL\n";
        $errcode = 1;
    }
}

# --DEPLOYMENT--
# each successful ontology is copied to deployment area

$n_errs = 0; # reset
if ($dry_run) {
    debug("dry-run -- no deploy");
}
else {
    foreach my $ont (@onts_to_deploy) {
        debug("deploying $ont");
        # TODO - copy main .obo and .owl to top level
        run("rsync $ont $target_dir");
        run("rsync $ont/$ont.obo $target_dir");
        run("rsync $ont/$ont.owl $target_dir");
    }
}

if ($n_errs > 0) {
    $errcode = 1;
}

exit $errcode;

# --SUBROUTINES--

# Run command in the shell
# globals affected: $n_errs, @errs
# returns non-zero if success
sub run {
    my $cmd = shift @_;
    debug("  RUNNING: $cmd");
    my $err = system("$cmd 2> ERR");
    if ($err) {
        my $err_text = `cat $err`;
        print STDERR "ERROR RUNNING: $cmd [in $ont ]\n";
        print STDERR $err_text;
        push(@errs, { ont => $ont,
                      cmd => $cmd,
                      err => $err,
                      err_text => $err_text });
        $n_errs ++;
    }    
    return !$err;
}

sub debug {
    my $t = `date`;
    chomp $t;
    print STDERR "$t :: @_\n";
}

# Each ontology has build metadata in a lookup table. See documentation at bottom of file for overview
#
# Keys:
#  - method : see below. Currently: obo2owl, owl2obo, vcs or archive
#  - source_url : required for obo2owl or owl2obo or archive methods. For obo<->owl the entire package is build from this one file. for archive, this is the location of the archive file.
#  - checkout : required for vcs method. The command to checkout from scratch the repo. Note this is suffixed with a loca dir name - do not add this to the cfg.
#  - system : required for vcs method. Currently one of: git OR svn
#  - path: required for archive, optional for vcs. This is the path in the archive that corresponds to the top level of the package. 
#  - infallible : if a build of this ontology fails, the exit code of this script is an error (resulting in red ball if run in jenkins)
#
# Notes:
#  For VCS, the checkout command should be to what would correspond to the top level of the package.
sub get_ont_info {
    return
        (
         go => {
             infallible => 1,
             method => 'vcs',
             system => 'svn',
             checkout => 'svn --ignore-externals co svn://ext.geneontology.org/trunk/ontology',
         },
         uberon => {
             infallible => 1,
             method => 'vcs',
             system => 'git',
             checkout => 'git clone https://github.com/cmungall/uberon.git',
         },
         sibo => {
             method => 'vcs',
             system => 'git',
             checkout => 'git clone https://github.com/obophenotype/sibo.git',
         },
         vt => {
             method => 'vcs',
             system => 'svn',
             checkout => 'svn co https://phenotype-ontologies.googlecode.com/svn/trunk/src/ontology/vt',
         },
         poro => {
             infallible => 1,
             method => 'vcs',
             system => 'svn',
             checkout => 'svn co https://porifera-ontology.googlecode.com/svn/trunk/src/ontology',
         },
         ro => {
             infallible => 1,
             method => 'vcs',
             system => 'svn',
             checkout => 'svn co https://obo-relations.googlecode.com/svn/trunk/src/ontology',
         },

         hao => {
             method => 'vcs',
             system => 'svn',
             checkout => 'svn co https://obo.svn.sourceforge.net/svnroot/obo/ontologies/trunk/HAO',
         },

         fypo => {
             infallible => 1,
             method => 'obo2owl',
             source_url => 'https://sourceforge.net/p/pombase/code/HEAD/tree/phenotype_ontology/releases/latest/fypo.obo?format=raw',
         },
         #fypo => {
         #    method => 'vcs',
         #    system => 'svn',
         #    checkout => 'svn checkout svn://svn.code.sf.net/p/pombase/code/phenotype_ontology/releases/latest',
         #},
         chebi => {
             infallible => 1,
             method => 'archive',
             path => 'archive/main',
             source_url => 'http://build.berkeleybop.org/job/build-chebi/lastSuccessfulBuild/artifact/*zip*/archive.zip',
         },
         envo => {
             infallible => 1,
             method => 'archive',
             path => 'archive',
             source_url => 'http://build.berkeleybop.org/job/build-envo/lastSuccessfulBuild/artifact/*zip*/archive.zip',
         },
         ma => {
             infallible => 1,
             method => 'obo2owl',
             source_url => 'ftp://ftp.informatics.jax.org/pub/reports/adult_mouse_anatomy.obo',
         },
         zfa => {
             infallible => 1,
             notes => 'may be ready to switch to vcs soon',
             method => 'obo2owl',
             source_url => 'https://zebrafish-anatomical-ontology.googlecode.com/svn/trunk/src/zebrafish_anatomy.obo',
         },

         zfs => {
             infallible => 1,
             method => 'obo2owl',
             source_url => 'https://developmental-stage-ontologies.googlecode.com/svn/trunk/src/zfs/zfs.obo',
         },

         fbbt => {
             infallible => 1,
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/animal_gross_anatomy/fly/fly_anatomy.obo',
         },




         # GENERIC

         fma => {
             method => 'obo2owl',
             source_url => 'http://obo.svn.sourceforge.net/viewvc/*checkout*/obo/fma-conversion/trunk/fma2_obo.obo',
         },
         imr => {
             method => 'obo2owl',
             source_url => 'http://www.inoh.org/ontologies/MoleculeRoleOntology.obo',
         },
         mfo => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/animal_gross_anatomy/fish/medaka_ontology.obo',
         },
         hom => {
             method => 'obo2owl',
             source_url => 'http://bgee.unil.ch/download/homology_ontology.obo',
         },
         pr => {
             method => 'obo2owl',
             source_url => 'ftp://ftp.pir.georgetown.edu/databases/ontology/pro_obo/pro.obo',
         },
         gro => {
             method => 'obo2owl',
             source_url => 'http://palea.cgrb.oregonstate.edu/viewsvn/Poc/trunk/ontology/OBO_format/po_anatomy.obo?view=co',
         },
         mp => {
             method => 'obo2owl',
             source_url => 'ftp://ftp.informatics.jax.org/pub/reports/MPheno_OBO.ontology',
         },
         symp => {
             method => 'obo2owl',
             source_url => 'http://gemina.svn.sourceforge.net/viewvc/gemina/trunk/Gemina/ontologies/gemina_symptom.obo',
         },
         pw => {
             method => 'obo2owl',
             source_url => 'ftp://rgd.mcw.edu/pub/data_release/pathway.obo',
         },
         vo => {
             method => 'obo2owl',
             source_url => 'http://www.violinet.org/vo',
         },
         bspo => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/caro/spatial.obo',
         },
         vario => {
             method => 'obo2owl',
             source_url => 'http://www.variationontology.org/download/VariO_0.979.obo',
         },
         eco => {
             method => 'obo2owl',
             source_url => 'http://evidenceontology.googlecode.com/svn/trunk/eco.obo',
         },
         iev => {
             method => 'obo2owl',
             source_url => 'http://www.inoh.org/ontologies/EventOntology.obo',
         },
         ypo => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/phenotype/yeast_phenotype.obo',
         },
         doid => {
             method => 'obo2owl',
             source_url => 'http://diseaseontology.svn.sourceforge.net/viewvc/*checkout*/diseaseontology/trunk/HumanDO.obo',
         },
         exo => {
             method => 'obo2owl',
             source_url => 'http://ctdbase.org/reports/CTD_exposure_ontology.obo',
         },
         lipro => {
             method => 'owl2obo',
             source_url => 'http://www.lipidprofiles.com/LipidOntology',
         },
         flu => {
             method => 'owl2obo',
             source_url => ' http://purl.obolibrary.org/obo/flu.owl',
         },
         to => {
             method => 'obo2owl',
             source_url => 'http://palea.cgrb.oregonstate.edu/viewsvn/Poc/trunk/ontology/collaborators_ontology/gramene/traits/trait.obo?view=co',
         },
         nmr => {
             method => 'owl2obo',
             source_url => 'https://msi-workgroups.svn.sourceforge.net/svnroot/msi-workgroups/ontology/NMR.owl',
         },
         miro => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/phenotype/mosquito_insecticide_resistance.obo',
         },
         tads => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/animal_gross_anatomy/tick_anatomy.obo',
         },
         swo => {
             method => 'owl2obo',
             source_url => 'http://theswo.svn.sourceforge.net/viewvc/theswo/trunk/src/release/swoinowl/swo_merged/swo_merged.owl',
         },
         rnao => {
             method => 'obo2owl',
             source_url => 'http://rnao.googlecode.com/svn/trunk/rnao.obo',
         },
         po => {
             notes => 'switch to vcs method',
             method => 'obo2owl',
             source_url => 'http://palea.cgrb.oregonstate.edu/viewsvn/Poc/tags/live/plant_ontology.obo?view=co',
         },
         caro => {
             notes => 'moving to owl soon',
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/caro/caro.obo',
         },
         ehda => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/animal_gross_anatomy/human/human-dev-anat-staged.obo',
         },
         emap => {
             notes => 'new url soon',
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/animal_gross_anatomy/mouse/EMAP.obo',
         },
         mod => {
             method => 'obo2owl',
             source_url => 'http://psidev.cvs.sourceforge.net/viewvc/psidev/psi/mod/data/PSI-MOD.obo',
         },
         mi => {
             method => 'obo2owl',
             source_url => 'http://psidev.cvs.sourceforge.net/viewvc/*checkout*/psidev/psi/mi/rel25/data/psi-mi25.obo',
         },
         aao => {
             method => 'obo2owl',
             source_url => 'http://github.com/seger/aao/raw/master/AAO_v2_edit.obo',
         },
         fbsp => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/taxonomy/fly_taxonomy.obo',
         },
         sep => {
             method => 'obo2owl',
             source_url => 'http://gelml.googlecode.com/svn/trunk/CV/sep.obo',
         },
         pato => {
             method => 'obo2owl',
             source_url => 'http://pato.googlecode.com/svn/trunk/quality.obo',
         },
         pco => {
             method => 'owl2obo',
             oort_args => '--no-subsets --reasoner hermit',
             source_url => 'http://purl.obolibrary.org/obo/pco.owl',
         },
         trans => {
             method => 'obo2owl',
             source_url => 'http://gemina.cvs.sourceforge.net/*checkout*/gemina/Gemina/ontologies/transmission_process.obo',
         },
         xao => {
             method => 'obo2owl',
             source_url => 'http://xenopus-anatomy-ontology.googlecode.com/svn/trunk/src/ontology/xenopus_anatomy_edit.obo',
         },
         mat => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/animal_gross_anatomy/multispecies/minimal_anatomical_terminology.obo',
         },
         mpath => {
             method => 'obo2owl',
             source_url => 'http://mpath.googlecode.com/svn/trunk/mpath.obo',
         },
         mao => {
             method => 'obo2owl',
             source_url => 'http://bips.u-strasbg.fr/LBGI/MAO/mao.obo',
         },
         wbphenotype => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/phenotype/worm_phenotype.obo',
         },
         mf => {
             method => 'owl2obo',
             source_url => 'http://purl.obolibrary.org/obo/mf.owl',
         },
         gaz => {
             method => 'obo2owl',
             oort_memory => '5G',
             oort_args => '--no-reasoner', # TODO - jvm
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/environmental/gaz.obo',
         },
         tgma => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/animal_gross_anatomy/mosquito_anatomy.obo',
         },
         bila => {
             method => 'obo2owl',
             source_url => 'http://4dx.embl.de/4DXpress_4d/edocs/bilateria_mrca.obo',
         },
         tao => {
             method => 'obo2owl',
             source_url => 'http://purl.obolibrary.org/obo/tao.obo',
         },
         fao => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/microbial_gross_anatomy/fungi/fungal_anatomy.obo',
         },
         wbbt => {
             method => 'obo2owl',
             source_url => 'http://github.com/raymond91125/Wao/raw/master/WBbt.obo',
         },
         mfoem => {
             method => 'owl2obo',
             source_url => 'http://purl.obolibrary.org/obo/mfoem.owl',
         },
         nif_cell => {
             method => 'owl2obo',
             source_url => 'http://ontology.neuinfo.org/NIF/BiomaterialEntities/NIF-Cell.owl',
         },
         bootstrep => {
             method => 'obo2owl',
             source_url => 'http://www.ebi.ac.uk/Rebholz-srv/GRO/GRO_latest',
         },
         eo => {
             method => 'obo2owl',
             source_url => 'http://palea.cgrb.oregonstate.edu/viewsvn/Poc/trunk/ontology/collaborators_ontology/plant_environment/environment_ontology.obo',
         },
         bto => {
             method => 'obo2owl',
             source_url => 'http://www.brenda-enzymes.info/ontology/tissue/tree/update/update_files/BrendaTissueOBO',
         },
         wbls => {
             notes => 'switch to vcs in dev repo?',
             method => 'obo2owl',
             source_url => 'https://raw.github.com/draciti/Life-stage-obo/master/worm_development.obo',
         },
         sbo => {
             method => 'obo2owl',
             source_url => 'http://www.ebi.ac.uk/sbo/exports/Main/SBO_OBO.obo',
         },
         uo => {
             method => 'obo2owl',
             source_url => 'http://unit-ontology.googlecode.com/svn/trunk/unit.obo',
         },
         iao => {
             method => 'owl2obo',
             source_url => 'http://purl.obolibrary.org/obo/iao.owl',
         },
         nif_dysfunction => {
             method => 'owl2obo',
             source_url => 'http://ontology.neuinfo.org/NIF/Dysfunction/NIF-Dysfunction.owl',
         },
         apo => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/phenotype/ascomycete_phenotype.obo',
         },
         ato => {
             method => 'obo2owl',
             source_url => 'http://ontology1.srv.mst.edu/sarah/amphibian_taxonomy.obo',
         },
         ehdaa => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/animal_gross_anatomy/human/human-dev-anat-abstract.obo',
         },
         fbdv => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/developmental/animal_development/fly/fly_development.obo',
         },
         cl => {
             method => 'obo2owl',
             source_url => 'http://purl.obolibrary.org/obo/cl.obo',
         },
         cvdo => {
             method => 'owl2obo',
             source_url => 'http://purl.obolibrary.org/obo/cvdo.owl',
         },
         omrse => {
             method => 'owl2obo',
             source_url => 'http://omrse.googlecode.com/svn/trunk/omrse/omrse.owl',
         },
         ms => {
             method => 'obo2owl',
             source_url => 'http://psidev.cvs.sourceforge.net/*checkout*/psidev/psi/psi-ms/mzML/controlledVocabulary/psi-ms.obo',
         },
         spd => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/animal_gross_anatomy/spider/spider_comparative_biology.obo',
         },
         pao => {
             method => 'obo2owl',
             source_url => 'po_anatomy.obo|http://palea.cgrb.oregonstate.edu/viewsvn/Poc/trunk/ontology/OBO_format/po_anatomy.obo?view=co',
         },
         nif_grossanatomy => {
             method => 'owl2obo',
             source_url => 'http://ontology.neuinfo.org/NIF/BiomaterialEntities/NIF-GrossAnatomy.owl',
         },
         #ev => {
         #    method => 'obo2owl',
         #    source_url => 'http://www.evocontology.org/uploads/Main/evoc_v2.7_obo.tar.gz',
         #},
         pgdso => {
             method => 'obo2owl',
             source_url => 'http://palea.cgrb.oregonstate.edu/viewsvn/Poc/trunk/ontology/OBO_format/po_temporal.obo?view=co',
         },
         ehdaa2 => {
             notes => 'SWITCH',
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/animal_gross_anatomy/human/human-dev-anat-abstract2.obo',
         },
         cheminf => {
             method => 'owl2obo',
             source_url => 'http://semanticchemistry.googlecode.com/svn/trunk/ontology/cheminf.owl',
         },
         aero => {
             method => 'owl2obo',
             source_url => 'http://purl.obolibrary.org/obo/aero.owl',
         },
         obi => {
             method => 'owl2obo',
             source_url => 'http://purl.obofoundry.org/obo/obi.owl',
         },
         oae => {
             method => 'owl2obo',
             source_url => 'http://svn.code.sf.net/p/oae/code/trunk/src/ontology/oae.owl',
         },
         nbo => {
             notes => 'SWITCH',
             method => 'owl2obo',
             source_url => 'http://behavior-ontology.googlecode.com/svn/trunk/behavior.owl',
         },
         tto => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/taxonomy/teleost_taxonomy.obo',
         },
         fbbi => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/experimental_conditions/imaging_methods/image.obo',
         },
         ddanat => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/microbial_gross_anatomy/dictyostelium/dictyostelium_anatomy.obo',
         },
         ero => {
             method => 'owl2obo',
             source_url => ' http://purl.obolibrary.org/obo/ero.owl',
         },
         rex => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/physicochemical/rex.obo',
         },
         zfs => {
             method => 'obo2owl',
             source_url => 'http://developmental-stage-ontologies.googlecode.com/svn/trunk/src/zfs/zfs.obo',
         },
         fbcv => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/vocabularies/flybase_controlled_vocabulary.obo',
         },
         idomal => {
             method => 'obo2owl',
             source_url => 'http://anobase.vectorbase.org/idomal/IDOMAL.obo',
         },
         taxrank => {
             method => 'obo2owl',
             source_url => 'https://phenoscape.svn.sourceforge.net/svnroot/phenoscape/trunk/vocab/taxonomic_rank.obo',
         },
         aeo => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/caro/aeo.obo',
         },
         ido => {
             method => 'owl2obo',
             source_url => ' http://purl.obolibrary.org/obo/ido.owl',
         },
         cdao => {
             method => 'owl2obo',
             source_url => 'http://purl.obolibrary.org/obo/cdao.owl',
         },
         pd_st => {
             method => 'obo2owl',
             source_url => 'http://4dx.embl.de/platy/obo/Pdu_Stages.obo',
         },
         vsao => {
             method => 'obo2owl',
             source_url => 'http://phenoscape.svn.sourceforge.net/svnroot/phenoscape/tags/vocab-releases/VSAO/vsao.obo',
         },
         vhog => {
             method => 'obo2owl',
             source_url => 'http://bgee.unil.ch/download/vHOG.obo',
         },
         kisao => {
             method => 'obo2owl',
             source_url => 'http://biomodels.net/kisao/KISAO',
         },
         so => {
             notes => 'SWITCH',
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/genomic-proteomic/so.obo',
         },
         hp => {
             method => 'obo2owl',
             source_url => 'http://compbio.charite.de/svn/hpo/trunk/src/ontology/human-phenotype-ontology.obo',
         },


        );
}

sub usage() {

    <<EOM;
build-obo-ontologies.pl [-d|--dry-run] [-s ONT]* [-t|--target-dir TARGET]

PURPOSE

Builds or mirrors ontologies from the OBO library. After execution,
the directory from which this program was run will contain directories
such as:

  ma/
  fbbt/
  go/

These will also be copied to TARGET

Each of these should correspond to the structure of the corresponding obolibrary purl. For example,

  go/
    subsets/
      goslim_plant.obo
      ...

This can be used to build local copies of ontologies to be used with
an OWL catalog (TODO: document owltools directory mapper here).

In the future it will also be used to replace the legacy Berkeley
obo2owl pipeline, and will populate the directories under here:

 http://berkeleybop.org/ontologies/

Which is currently the default fallback for unregistered purls (and
registered purls are welcome to redirect here).

HOW IT WORKS

The script uses an internal registry to determine how to build each
ontology. There are currently 3 methods:

  * obo2owl
  * owl2obo
  * archive
  * vcs

The "obo2owl" method is intended for ontologies that publish a single
obo file, and do not take control of building owl or other derived
files in a obolibrary compliant way. It runs Oort to produce a
standard layout.

The owl2obo method also runs oort.

The vcs method is used when an ontology publishes release and derived
files in a consistent directory structure. It simply checks out the
project and rsyncs the specified subdirectory to the target. Currently
this is git or svn only.

The archive method is used when an ontology publishes the standard
files in a standard structure as an archive file (currently zip only,
but easily extended to tgz). This is currently used for ontologies
that are built via Jenkins, as jenkins publishes targets in a zip
archive.

HISTORY AND COORDINATION WITH OBO-REGISTRY

Historically, the Berkeley obo2owl pipeline consumed the
ontologies.txt file and generated owl for all obo ontologies, using
the "source" or "download" tag. This caused a number of problems - the
same field was used by some legacy applications that could not consume
more "advanced" obo meaning the build pipeline produced owl from
"dumbed down" versions of ontologies.

The ontologies.txt registry method is being overhauled, but there is
still a need for a build pipeline that handles some of the
peculiarities of each ontology. In the future every ontology should
use oort or a similar tool to publish the full package, but an interim
solution is required. Even then, some ontologies require a place to
distribute their package (historically VCS has been used as the
download mechanism but this can be slow, and it can be inefficient to
manage multiple derived rdf/xml owl files in a VCS).

Once this new script is in place, the contents of
berkeleybop.org/ontologies/ will be populated using one of the above
methods for each ontology. Each ontology is free to either ignore this
and redirect their purls as they please, or alternatively, point their
purls at the central berkeley location.

The decision to keep the registry as a hash embedded in this script
allows for programmatic configurability, which is good for a lot of
important ontologies that do not yet publish their entire package in a
library-compliant way. In future this script should become less
necessary.

SEE ALSO

This may be a better long term approach for publishing ontologies:

 * http://gitorious.org/ontology-maven-plugins/ninox-maven-plugin

EOM
}
