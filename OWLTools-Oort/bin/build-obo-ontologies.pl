#!/usr/bin/perl -w
use strict;

my %selection = ();
my $dry_run = 0;
while ($ARGV[0] && $ARGV[0] =~ /^\-/) {
    my $opt = shift @ARGV;
    if ($opt eq '-h') {
        print usage();
    }
    elsif ($opt eq '-s' || '--select') {
        $selection{shift @ARGV} = 1;
    }
    elsif ($opt eq '-d' || '--dry-run') {
        $dry_run = 1;
    }
    else {
        die "unknown option: $opt";
    }
}

my %ont_info = get_ont_info();

if (!(-d 'src')) {
    run("mkdir src");
}

my $ont;
my $n_errs = 0;
my @errs = ();
my @onts_to_deploy = ();

foreach $ont (keys %ont_info) {
    if (keys %selection) {
        next unless $selection{$ont};
    }
    debug("ONTOLOGY: $ont");
    my $info = $ont_info{$ont};
    my $method = lc($info->{method});
    my $source_url = $info->{source_url};

    my $success = 0;
    if ($method eq 'vcs') {
        my $dir = "stage-$ont";
        if (-d $dir) {
            # already checked out
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
            run("cd $dir && $cmd");
        }
        else {
            # initial checkout
            my $cmd = $info->{checkout};
            run("$cmd $dir");
        }
        my $srcdir = $dir;
        if ($info->{subdir}) {
            $srcdir .= "/".$info->{subdir};
        }
        # TODO - custom post-commands

        # copy from staging to target
        $success = run("rsync -avz --delete $srcdir/* $ont/");
    }

    if ($method eq 'obo2owl') {
        my $SRC = "src/$ont.obo";
        my @OORT_ARGS = "--reasoner elk";
        if ($info->{oort_args}) {
            @OORT_ARGS = $info->{oort_args};
        }
        run("wget --no-check-certificate $source_url -O $SRC");
        $success = run("ontology-release-runner --skip-release-folder --skip-format owx --allow-overwrite --outdir $ont @OORT_ARGS --asserted --simple $SRC");
    }

    if ($method eq 'owl2obo') {
        # TODO - reuse code with obo2owl
        my $SRC = "src/$ont.owl";
        my @OORT_ARGS = "--reasoner elk";
        if ($info->{oort_args}) {
            @OORT_ARGS = $info->{oort_args};
        }
        run("wget --no-check-certificate $source_url -O $SRC");
        # TODO - less strict mode for owl2obo
        $success = run("ontology-release-runner --repair-cardinality --skip-release-folder --skip-format owx --allow-overwrite --outdir $ont @OORT_ARGS --asserted --simple $SRC");
    }

    if ($method eq 'archive') {
        my $SRC = "src/$ont-archive.zip";
        my $path = $info->{path};
        if (!$path) {
            die "must set path for $ont";
        }
        run("wget --no-check-certificate $source_url -O $SRC");
        run("unzip -o $SRC");
        run("rsync -avz --delete $path/* $ont/");
    }


    if ($method eq 'custom') {
        die;
    }
    if ($success) {
        push(@onts_to_deploy, $ont);
    }
}

# REPORT
print "Build completed\n";
print "N_Errors: $n_errs\n";
foreach my $err (@errs) {
    print "ERROR: $err->{ont} $err->{cmd} $err->{err_text}\n";
}

# DEPLOY

if ($dry_run) {
    debug("dry-run -- no deploy");
}
else {
    foreach my $ont (@onts_to_deploy) {
        debug("deploying $ont");
        #run("rsync $ont");
    }
}

exit 0;

sub run {
    my $cmd = shift @_;
    debug("  RUNNING: $cmd");
    my $err = system("$cmd 2> ERR");
    if ($err) {
        my $err_text = `cat $err`;
        print STDERR "ERROR RUNNING: $cmd [in $ont]\n";
        print STDERR $err_text;
        push(@errs, { ont => $ont,
                      cmd => $cmd,
                      err => $err,
                      err_text => $err_text });
        $n_errs ++;
    }    
    return $err;
}

sub debug {
    my $t = `date`;
    chomp $t;
    print STDERR "$t :: @_\n";
}

sub get_ont_info {
    return
        (
         go => {
             method => 'vcs',
             system => 'svn',
             checkout => 'svn --ignore-externals co svn://ext.geneontology.org/trunk/ontology',
         },
         uberon => {
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
             method => 'vcs',
             system => 'svn',
             checkout => 'svn co https://poro.googlecode.com/svn/trunk/src/ontology',
         },
         ro => {
             method => 'vcs',
             system => 'svn',
             checkout => 'svn co https://obo-relations.googlecode.com/svn/trunk/src/ontology',
         },
         hao => {
             method => 'vcs',
             system => 'svn',
             source_url => 'https://obo.svn.sourceforge.net/svnroot/obo/HAO/trunk',
         },
         #zfa => {
         #    method => 'vcs',
         #    system => 'svn',
         #    checkout => 'svn co https://zebrafish-an.googlecode.com/svn/trunk/src/ontology/vt',
         #},

         fypo => {
             method => 'obo2owl',
             source_url => 'https://sourceforge.net/p/pombase/code/HEAD/tree/phenotype_ontology/releases/latest/fypo.obo?format=raw',
         },
         #fypo => {
         #    method => 'vcs',
         #    system => 'svn',
         #    checkout => 'svn checkout svn://svn.code.sf.net/p/pombase/code/phenotype_ontology/releases/latest',
         #},
         chebi => {
             method => 'archive',
             path => 'archive/main',
             source_url => 'http://build.berkeleybop.org/job/build-chebi/lastSuccessfulBuild/artifact/*zip*/archive.zip',
         },
         envo => {
             method => 'archive',
             path => 'archive',
             source_url => 'http://build.berkeleybop.org/job/build-envo/lastSuccessfulBuild/artifact/*zip*/archive.zip',
         },
         ma => {
             method => 'obo2owl',
             source_url => 'ftp://ftp.informatics.jax.org/pub/reports/adult_mouse_anatomy.obo',
         },
         zfa => {
             method => 'obo2owl',
             source_url => 'https://zebrafish-anatomical-ontology.googlecode.com/svn/trunk/src/zebrafish_anatomy.obo',
         },

         zfs => {
             method => 'obo2owl',
             source_url => 'https://developmental-stage-ontologies.googlecode.com/svn/trunk/src/zfs/zfs.obo',
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
         fbbt => {
             method => 'obo2owl',
             source_url => 'http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/anatomy/gross_anatomy/animal_gross_anatomy/fly/fly_anatomy.obo',
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
             oort_args => '', # TODO - jvm
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
             source_url => 'po_temporal.obo|http://palea.cgrb.oregonstate.edu/viewsvn/Poc/trunk/ontology/OBO_format/po_temporal.obo?view=co',
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
