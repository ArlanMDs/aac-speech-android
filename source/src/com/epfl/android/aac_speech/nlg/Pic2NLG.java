package com.epfl.android.aac_speech.nlg;

import android.R.bool;
import android.app.Activity;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;

import com.epfl.android.aac_speech.lib.ArrayUtils;
import com.epfl.android.aac_speech.nlg.Pic2NLG.ActionType;

import android.util.Log;

import simplenlg.aggregation.ClauseCoordinationRule;
import simplenlg.features.Feature;
import simplenlg.features.InternalFeature;
import simplenlg.features.LexicalFeature;
import simplenlg.features.NumberAgreement;
import simplenlg.features.Tense;
import simplenlg.features.french.FrenchFeature;
import simplenlg.features.french.FrenchLexicalFeature;
import simplenlg.framework.CoordinatedPhraseElement;
import simplenlg.framework.Language;
import simplenlg.framework.LexicalCategory;
import simplenlg.framework.NLGElement;
import simplenlg.framework.NLGFactory;
import simplenlg.framework.WordElement;
import simplenlg.lexicon.Lexicon;

import simplenlg.lexicon.french.XMLLexiconFast;
import simplenlg.lexicon.french.XMLLexicon;

import simplenlg.phrasespec.AdjPhraseSpec;
import simplenlg.phrasespec.AdvPhraseSpec;
import simplenlg.phrasespec.NPPhraseSpec;
import simplenlg.phrasespec.PPPhraseSpec;
import simplenlg.phrasespec.SPhraseSpec;
import simplenlg.phrasespec.VPPhraseSpec;
import simplenlg.realiser.Realiser;
import simplenlg.syntax.french.VerbPhraseHelper;

import com.epfl.android.aac_speech.data.PicWordAction;

public class Pic2NLG {

	public enum ActionType {
		NOUN, CLITIC_PRONOUN, VERB, ADVERB, TENSE_PRESENT, TENSE_PAST, TENSE_FUTURE, TENSE_FUTUR_PROCHE, NUMBER_AGREEMENT, NEGATED, ADJECTIVE, PREPOSITION, QUESTION, DOT, CATEGORY
	};

	public static Lexicon lexicon;
	public static NLGFactory factory;
	public static Realiser realiser;

	private static String LangFeature_FutureProche_MODAL = "aller";

	public static void initEnglish() {
		LangFeature_FutureProche_MODAL = "go";
		lexicon = new simplenlg.lexicon.english.XMLLexicon();
		factory = new NLGFactory(lexicon);
		realiser = new Realiser();
	}

	public static void initFrench() {
		Log.d("simpleNLG XmlLexicon", " start");

		// new XmlLexicon
		lexicon = new simplenlg.lexicon.french.XMLLexiconFast();

		// original XmlLexicon
		// lexicon = new simplenlg.lexicon.french.XMLLexicon();
		Log.d("simpleNLG XmlLexicon", " end");

		factory = new NLGFactory(lexicon);
		realiser = new Realiser();
		Log.d("simpleNLG Factory-and-Releasizer", " end");

	}

	public Pic2NLG() {
		initFrench();
	}

	public Pic2NLG(String lang) {
		if (lang.equals("en")) {
			initEnglish();
		} else {
			initFrench();
		}
	}

	private static void log(String msg) {
		System.out.println(msg);
	}

	public static NumberAgreement getNumberAgreement(String data) {
		return (data.equals("plural")) ? NumberAgreement.PLURAL
				: NumberAgreement.SINGULAR;
	}

	/**
	 * Returns string representation of the verb element in it "base" form
	 * (without additional features removed).
	 * 
	 * TODO: may I include reflexivity?
	 * 
	 * @param verb_elm
	 * @return
	 */
	private static String getVerbMainFormAsString(NLGElement verb_elm) {
		String verb = "";
		if ((verb_elm instanceof VPPhraseSpec)) {
			verb = verb_elm.getFeatureAsString(InternalFeature.HEAD);
			NLGElement verb_elm2 = verb_elm
					.getFeatureAsElement(InternalFeature.HEAD);
			if (verb_elm2 instanceof WordElement) {
				verb_elm = verb_elm2;
			}
		}
		if (verb_elm instanceof WordElement) {
			verb = ((WordElement) verb_elm).getBaseForm();
		}
		log("Verb head is " + verb);

		return verb;

	}

	/**
	 * 
	 * @param phrases
	 * @return
	 * 
	 *         TODO: parse into a tree first apply some smart rules if possible
	 *         e.g. preposition detection
	 * 
	 *         The components of a declarative clause are typically arranged in
	 *         the following order (though not all components are always
	 *         present):
	 * 
	 *         see: http://en.wikipedia.org/wiki/French_grammar (word order --
	 *         adjectives are considered as part of object and subject)
	 * 
	 *         also see paper: Morris Salkoff, A context-free grammar of French @
	 *         COLING '80 Proceedings of the 8th conference on Computational
	 *         linguistics http://dl.acm.org/citation.cfm?id=990182
	 * 
	 *         Adverb(s) Subject ne (usually a marker for negation, though it
	 *         has some other uses) First- and second-person object pronoun, or
	 *         the third-person reflexive pronoun (any of me, te, nous, vous,
	 *         se) Third-person human direct-object pronoun (any of le, la, les)
	 *         Third-person human indirect-object pronoun (either lui or leur)
	 *         The pronoun y The pronoun en Finite verb (may be an auxiliary)
	 *         Adverb(s) The pronoun rien (if not subject) Main verb (if the
	 *         finite verb is an auxiliary) Adverb(s) and object(s)
	 */
	public String convertPhrasesToNLG(ArrayList<PicWordAction> phrases) {

		/*
		 * TODO: support multiple clauses
		 */

		/* reverse the list into a stack */
		Stack<PicWordAction> stack0 = new Stack<PicWordAction>();
		Stack<PicWordAction> stack = new Stack<PicWordAction>();
		stack0.addAll(phrases);
		log(stack0.toString());

		while (!stack0.isEmpty()) {
			stack.push(stack0.pop());
		}

		/*
		 * [NOUN (ADjective (adverb adj) etc) ] VERB [ [adverb... [adj]] ] [noun
		 * - optional!]
		 */

		/* for simplicity now, this will store anything before the last dot */
		String prefixClause = "";
		SPhraseSpec clause = factory.createClause();
		// NPPhraseSpec currentNounPrase = factory.createNounPhrase();
		boolean is_question = false;

		/*
		 * These POS will pop out from stack immediately as they require
		 * additional processing, like matching dependent NounPhrase
		 */
		ActionType pop_immediately_POS[] = { ActionType.PREPOSITION,
				ActionType.ADVERB };

		/*
		 * We use a simple simple greedy algorithm: for now we assume to have
		 * only one clause -- therefore up to two verbs: (modal) verb. So we may
		 * safely mach these first.
		 * 
		 * try to greedily match NounPhrase if not then:
		 * 
		 * - adjective, adverb as clause modifiers
		 */
		while (!stack.isEmpty()) {
			// Greedily the noun phrase first (if any) -- this is needed
			// because, for instance the adjevctive as complement to the clause
			// must not be mixed with adjective belonging to an noun, and in
			// french adjective may go both before and after a noun

			// TODO: To fix the problem with me, te, etc we do not allow subject
			// to consist of more than one coordinate for now
			// TODO: long term solution may be to create a separate action_type
			// for CLITIC_PRONOUN (je [te] donne, that should (?) allow to have
			// coordinated
			// subject and pronouns at the same type

			// TODO: for words like tomorrow, today -- these shall not be just
			// Nouns.
			// as we do not need to guess specifier for a time of clause
			// e.g. je mange de bonbons apres-midi

			NLGElement currentNounPrase;

			currentNounPrase = matchCoordinatedNounPhraseList(stack);

			if (currentNounPrase != null) {
				log("NP matched:" + currentNounPrase);

				if (clause.getSubject() == null) {
					clause.setSubject(currentNounPrase);
					// TODO: plural
					log("set subject:" + currentNounPrase);
				} else {
					/*
					 * if object is already set, i.e. in je [te] aime beaucaup,
					 * the [te] is a direct object and have will be set as
					 * object.
					 * 
					 * however afterwards if we had indirect object, we have to
					 * make sure it would not override the earlier
					 * 
					 * -- in short, this append one more object coordinate
					 */

					log("setting object to:" + currentNounPrase);

					clauseAddObject(clause, currentNounPrase);

					if (clause.getObject() != null)
						log("clause.object afterwards:" + clause.getObject());

					String intelligent_guess = intelligentGuessSpecifier(clause);

					if (currentNounPrase instanceof CoordinatedPhraseElement) {
						/* set determiner to each of coordinated phrases */
						for (NLGElement NP : currentNounPrase.getChildren()) {

							if (((NPPhraseSpec) NP)
									.getFeature(InternalFeature.SPECIFIER) == null) {

								((NPPhraseSpec) NP)
										.setDeterminer(intelligent_guess);
							}
						}
					}
					if (currentNounPrase instanceof NPPhraseSpec
							&& ((NPPhraseSpec) currentNounPrase)
									.getFeature(InternalFeature.SPECIFIER) == null) {
						((NPPhraseSpec) currentNounPrase)
								.setDeterminer(intelligent_guess);

					}
				}
			}

			if (stack.isEmpty())
				break;

			PicWordAction action = stack.peek();

			switch (action.type) {

			case CLITIC_PRONOUN:
				clauseAddObject(clause, action.element);
				break;

			case VERB:
				/*
				 * with simpleNLG, Reflexivity must be handled at the level of
				 * clause we are adding the Reflexive feature to the verb, which
				 * is not normally used, so we have to remove it afterwards (for
				 * forward compatability)
				 */

				if (action.element.hasFeature(LexicalFeature.REFLEXIVE)) {
					if (action.element
							.getFeatureAsBoolean(LexicalFeature.REFLEXIVE)) {
						clause.setIndirectObject("se");
					}

					// TODO:
					// action.element.removeFeature(LexicalFeature.REFLEXIVE);
					// just not to create copy of object I don't remove this
					// feature now
				}

				if (clause.getVerb() == null) {
					clause.setVerb(action.element);
					log("verb:" + action.element);

				} else {
					/**
					 * TODO this may still be more complex regarding the context
					 * (TODO) but for now we assume that subsequent VERB become
					 * a MODAL.
					 * 
					 * TODO: are there cases then we may have a verb following
					 * which is not a modal? [if it's not sub-clause, e.g. je
					 * veux que tu manges] e.g. veux, peux, dois:
					 * http://www.laits.utexas.edu/tex/gr/vm1.html
					 * 
					 * however, so far we do not support sub-clauses, so this do
					 * not cause a problem
					 */

					/* exchange the verb with modal */
					clause.setFeature(Feature.MODAL, clause.getVerb());
					clause.setVerb(action.element);

					log("modal:" + action.element);

				}
				break;

			/** TODO */
			case PREPOSITION:
				PPPhraseSpec prepPhrase = factory.createPrepositionPhrase();
				prepPhrase.setPreposition(action.element);

				// remove preposition phrase from stack immediately
				stack.pop();

				// TODO: buildNounPhrase from anything afterwards
				NLGElement nounPhrase = matchNounPhrase(stack);

				if (nounPhrase != null)
					prepPhrase.addComplement(nounPhrase);

				clause.addComplement(prepPhrase);

				// attach as a complement to current clause
				break;

			case QUESTION:
				// TODO: this is very very primitive way of forming questions
				// 1) long click on question may give more options
				// 2) we may have different complex questions and may have to
				// set
				// the object etc

				String text = realiser.realiseSentence(clause);
				text = text.replace('.', '?');

				prefixClause = prefixClause + text + " ";

				// reset defaults
				clause = factory.createClause();

				break;

			case DOT:
				prefixClause = prefixClause + realiser.realiseSentence(clause)
						+ " ";
				// reset defaults
				clause = factory.createClause();
				is_question = false;
				break;

			case NEGATED:
				clause.setFeature(Feature.NEGATED, true);
				log("negated:" + action.data);

				break;

			case TENSE_PRESENT: /* Present is not needed as is by default */
			case TENSE_FUTURE:
			case TENSE_PAST:
			case TENSE_FUTUR_PROCHE:

				ActionType actType = action.type;

				Tense tense = (actType == ActionType.TENSE_PAST) ? Tense.PAST
						: (actType == ActionType.TENSE_FUTURE) ? Tense.FUTURE
								: Tense.PRESENT;

				// For "futur proche" we assign just allez a modal
				if (actType == ActionType.TENSE_FUTUR_PROCHE) {
					// TODO: A additional hack seems to be needed as the
					// initial
					// verb gets "ruined" by transforming it to some other forms
					// prior to knowing about intension of "futur proche"!
					// TODO: futur proche do not work if selected before the
					// word

					clause.setVerb(getVerbMainFormAsString(clause.getVerb()));
					clause.setFeature(Feature.MODAL,
							LangFeature_FutureProche_MODAL);

					log("tense: futur proche");

				} else {
					clause.setFeature(Feature.TENSE, tense);
					log("tense:" + tense);

				}
				log("clause: " + clause);

				break;

			case ADVERB:
				/**
				 * The current solution is to check if subsequent elements are
				 * adverbs too, if so we join them all into one.
				 * 
				 * TODO: check if this may lead to an issue anywhere
				 */
				NLGElement adverb = lexicon.getWord(action.data,
						LexicalCategory.ADVERB);
				stack.pop();

				/*
				 * TODO: there may be multiple pre-modifiers, e.g. vraiment tres
				 * vite, but this is sufficiently bizare to care for right now
				 */
				if (!stack.isEmpty()) {
					PicWordAction next_pos = stack.peek();
					if (next_pos.type == ActionType.ADVERB) {
						AdvPhraseSpec advPhrase = factory.createAdverbPhrase();
						advPhrase.addPreModifier(adverb);
						advPhrase.setAdverb(next_pos.element);
						adverb = advPhrase;
						stack.pop();
					}

				}

				clause.addComplement(adverb);
				// clause.addModifier(adverb);
				break;

			case ADJECTIVE:
				// this is the case of adjective that applies only directly to
				// the clause (there's no noun), therefore we must first
				// greedily match a noun phrase above
				clause.setComplement(action.element);
				break;

			default:
				break;
			}

			/* pop a matched action */
			if (!ArrayUtils.contains(pop_immediately_POS, action.type))
				stack.pop();
		}

		/* Sometimes the structure may not be ready yet, e.g. negation only */
		String text = "";
		try {

			text = realiser.realiseSentence(clause);

			// dot at the end of sentence looks misleading as we have a
			// button for, so remove it
			text = text.replace('.', ' ');

		} catch (Exception e) {
			// TODO: handle exception
			System.out.println(e);
			e.printStackTrace();
			// Log.e("Pic2NLG", "cant release sentence");
		}
		return prefixClause + text;

	}

	/**
	 * TODO: it is not clear how to handle direct object pronoun together with
	 * (indirect object?? or shall that be complement and I just have to get
	 * proper tagging)
	 * 
	 * e.g. je te coifferai aujourdhui apres-midi
	 * 
	 * [now we get je coifferai toi et aujourdhui apres-midi ]
	 * 
	 * 
	 * 
	 * @param clause
	 * @param object_to_add
	 */
	private void clauseAddObject(SPhraseSpec clause, NLGElement object_to_add) {
		NLGElement currentObject;
		if ((currentObject = clause.getObject()) != null) {
			CoordinatedPhraseElement objectCoordinate;
			if (currentObject instanceof CoordinatedPhraseElement) {
				objectCoordinate = (CoordinatedPhraseElement) currentObject;
			} else {
				objectCoordinate = factory.createCoordinatedPhrase();
				objectCoordinate.addCoordinate(currentObject);
			}

			/*
			 * if (object_to_add instanceof CoordinatedPhraseElement) {
			 * CoordinatedPhraseElement to_add = (CoordinatedPhraseElement)
			 * object_to_add;
			 * 
			 * for (NLGElement child : to_add.getChildren()) {
			 * objectCoordinate.addCoordinate(child); }
			 * 
			 * } else { objectCoordinate.addCoordinate(object_to_add); }
			 */

			objectCoordinate.addCoordinate(object_to_add);

			clause.setObject(objectCoordinate);
		} else {
			clause.setObject(object_to_add);
		}
	}

	private String intelligentGuessSpecifier(SPhraseSpec clause) {
		log("starting intellingent guess for specifier!");

		String verb = getVerbMainFormAsString(clause.getVerb());

		// TODO: refator this thng as getVerbAsString
		// .getFeatureAsString(InternalFeature.HEAD);

		String intelligent_guess = "";

		/*
		 * Some come from here, TODO: finish http://french.about.
		 * com/library/prepositions/bl_prep_a_verb.htm
		 */
		String[] verb_a_IndObj = { "habiter", "aider", "apprendre", "arriver",
				"commencer", "aller" /*
									 * TODO: add more
									 */};

		/**
		 * de + noun Indirect Object http://french.about.com/library
		 * /prepositions/bl_prep_de_verb2.htm
		 * 
		 * TODO: handle reflexivity: "s'occuper","se méfier", "s'étonner",
		 * "se souvenir", se tromper de,
		 * 
		 * TODO: handle modals/double words: avoir besoin
		 */

		String[] de_verb_IndObj = { "avoir besoin", "avoir envie", "dépendre",
				"douter", "féliciter", "jouer", "jouir", "manquer", "partir",
				"penser", "profiter", "punir", "recompenser", "remercier",
				"rire", "vivre",

				/*
				 * these there not specified in grammar rules, but seem to be
				 * good guesses
				 */
				"boire", "vouloir", "manger" };

		/* TODO: ones with infinitive */
		String[] de_verb_infinitive = {};

		List<String> a_verbs_list = Arrays.asList(verb_a_IndObj);
		List<String> de_verb_IndObj_list = Arrays.asList(de_verb_IndObj);

		if (a_verbs_list.contains(verb))
			intelligent_guess = "à";
		if (de_verb_IndObj_list.contains(verb))
			intelligent_guess = "de";

		log("verb: " + verb + "  guessed:" + intelligent_guess);
		return intelligent_guess;
	}

	/**
	 * Builds a list of coordinated noun phrases in a greedy way
	 * 
	 * @param "part of speech" tagged words (POS)
	 * @return list of coordinated Noun phrases or null if there's no noun(s)
	 * 
	 */
	private NLGElement matchCoordinatedNounPhraseList(Stack<PicWordAction> stack) {
		CoordinatedPhraseElement NP_list = factory.createCoordinatedPhrase();
		NPPhraseSpec current_NP;
		Boolean found = false;
		while ((current_NP = matchNounPhrase(stack)) != null) {
			NP_list.addCoordinate(current_NP);
			found = true;
		}
		// NP_list.getChildren().size() == 0
		if (!found)
			return null;
		/*
		 * if only one -- it must be NP but not coordinated NP e.g. Moi et mes
		 * amies ... ==> Moi
		 * 
		 * TODO: fix Je!
		 */
		if (NP_list.getChildren().size() == 1)
			return NP_list.getChildren().get(0);

		return NP_list;
	}

	/**
	 * Builds a noun phrase in a greedy way only one noun allowed in noun phrase
	 * -- other will get coordinated. TODO: check the cases of names of
	 * consisting of multiple items
	 * 
	 * @param "part of speech" tagged words (POS)
	 * @return Noun phrase or null if there's no noun
	 * 
	 */
	private NPPhraseSpec matchNounPhrase(Stack<PicWordAction> stack) {
		NPPhraseSpec currentNounPrase = factory.createNounPhrase();

		/* What part of speech are not allowed inside NounPhrase */

		ActionType allowed_POS[] = { ActionType.NOUN, ActionType.ADJECTIVE,
				ActionType.NUMBER_AGREEMENT };

		/**
		 * Assertion 1: Noun phrase must have a Noun, otherwise the current list
		 * may be e.g. Adjective phrase: while adjective is also accepted in
		 * Noun phrase, it cannot go alone on simpleNLG
		 */
		Boolean noun_exist = false;

		for (int i = stack.size() - 1; i >= 0; i--) {
			PicWordAction item = stack.get(i);

			// log("NP N containement check: " + item);

			if (!ArrayUtils.contains(allowed_POS, item.type)) {
				break;
			}

			if (item.type == ActionType.NOUN)
				noun_exist = true;
		}
		if (!noun_exist)
			return null;

		PicWordAction action;

		int nNounsFound = 0;
		while (!stack.isEmpty()) {
			action = stack.peek();

			switch (action.type) {
			case NOUN:
				/*
				 * we match only the first noun, the subsequent noun will be
				 * part of second coordinated NP
				 */
				nNounsFound++;

				if (nNounsFound == 1) {
					currentNounPrase.setNoun(action.element);
				}

				break;

			case NUMBER_AGREEMENT:
				currentNounPrase.setFeature(Feature.NUMBER,
						getNumberAgreement(action.data));
				break;

			case ADJECTIVE:
				currentNounPrase.addModifier(action.element);

				break;

			default:
				/*
				 * not allowed action, the NP is over. TODO: handle if NP is not
				 * set. e.g. no NP then try adjective phrase on the clause
				 */

				return currentNounPrase;
			}

			/**
			 * If we found a second noun, break the processing
			 */
			if (nNounsFound > 1) {
				break;
			}
			/* the current POS was accepted/matched, so Pop if from stack */
			stack.pop();
		}

		return currentNounPrase;
	}

	public Boolean hasSubjectBeenSelected(ArrayList<PicWordAction> phrases) {
		Boolean have_subject = false;
		for (PicWordAction phrase : phrases) {
			if (phrase.type == ActionType.NOUN)
				have_subject = true;

			if (phrase.type == ActionType.DOT
					|| phrase.type == ActionType.QUESTION)
				have_subject = false;
		}
		return have_subject;
	}

}
