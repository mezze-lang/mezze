// Generated from /Users/shriyans/Projects/funk/parser/src/main/antlr/Funk.g4 by ANTLR 4.9.2
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast"})
public class FunkParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.9.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		LET=1, TYPE=2, WITH=3, IF=4, THEN=5, ELSE=6, MATCH=7, HANDLE=8, RESUME=9, 
		EFFECT=10, ABILITY=11, WHERE=12, MODULE=13, EXPOSE=14, ACCESS=15, AS=16, 
		DO=17, PLUS=18, MINUS=19, MULTIPLY=20, DIVIDE=21, CONCAT=22, ASSIGN=23, 
		PIPE=24, ARROW=25, PLACEHOLDER=26, WILDCARD=27, ELLIPSIS=28, LPAREN=29, 
		RPAREN=30, LBRACE=31, RBRACE=32, LBRACKET=33, RBRACKET=34, PIPE_DELIM=35, 
		COLON=36, COMMA=37, DOT=38, Integer=39, Float=40, String=41, Boolean=42, 
		Identifier=43, Comment=44, WS=45, NEWLINE=46;
	public static final int
		RULE_program = 0, RULE_statement = 1, RULE_moduleDeclaration = 2, RULE_exposeDeclaration = 3, 
		RULE_exposeList = 4, RULE_exposeItem = 5, RULE_accessDeclaration = 6, 
		RULE_accessList = 7, RULE_accessItem = 8, RULE_letBinding = 9, RULE_typeDeclaration = 10, 
		RULE_typeParams = 11, RULE_typeDefinition = 12, RULE_variantType = 13, 
		RULE_variantConstructor = 14, RULE_qualifiedIdentifier = 15, RULE_effectDeclaration = 16, 
		RULE_abilityDeclaration = 17, RULE_effectSignature = 18, RULE_resumeStatement = 19, 
		RULE_expressionStatement = 20, RULE_expression = 21, RULE_pipeExpression = 22, 
		RULE_matchExpression = 23, RULE_matchCase = 24, RULE_pattern = 25, RULE_recordPattern = 26, 
		RULE_recordPatternEntry = 27, RULE_handleExpression = 28, RULE_handleCase = 29, 
		RULE_ifExpression = 30, RULE_updateExpression = 31, RULE_additiveExpression = 32, 
		RULE_multiplicativeExpression = 33, RULE_memberAccessExpression = 34, 
		RULE_primaryExpression = 35, RULE_lambdaExpression = 36, RULE_blockExpression = 37, 
		RULE_doExpression = 38, RULE_recordLiteral = 39, RULE_recordEntry = 40, 
		RULE_recordType = 41, RULE_recordTypeEntry = 42, RULE_typeAnnotation = 43, 
		RULE_functionType = 44, RULE_effectList = 45, RULE_literal = 46;
	private static String[] makeRuleNames() {
		return new String[] {
			"program", "statement", "moduleDeclaration", "exposeDeclaration", "exposeList", 
			"exposeItem", "accessDeclaration", "accessList", "accessItem", "letBinding", 
			"typeDeclaration", "typeParams", "typeDefinition", "variantType", "variantConstructor", 
			"qualifiedIdentifier", "effectDeclaration", "abilityDeclaration", "effectSignature", 
			"resumeStatement", "expressionStatement", "expression", "pipeExpression", 
			"matchExpression", "matchCase", "pattern", "recordPattern", "recordPatternEntry", 
			"handleExpression", "handleCase", "ifExpression", "updateExpression", 
			"additiveExpression", "multiplicativeExpression", "memberAccessExpression", 
			"primaryExpression", "lambdaExpression", "blockExpression", "doExpression", 
			"recordLiteral", "recordEntry", "recordType", "recordTypeEntry", "typeAnnotation", 
			"functionType", "effectList", "literal"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'let'", "'type'", "'with'", "'if'", "'then'", "'else'", "'match'", 
			"'handle'", "'resume'", "'effect'", "'ability'", "'where'", "'module'", 
			"'expose'", "'access'", "'as'", "'do'", "'+'", "'-'", "'*'", "'/'", "'++'", 
			"'='", "'|>'", "'->'", "'@'", "'_'", "'...'", "'('", "')'", "'{'", "'}'", 
			"'['", "']'", "'|'", "':'", "','", "'.'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, "LET", "TYPE", "WITH", "IF", "THEN", "ELSE", "MATCH", "HANDLE", 
			"RESUME", "EFFECT", "ABILITY", "WHERE", "MODULE", "EXPOSE", "ACCESS", 
			"AS", "DO", "PLUS", "MINUS", "MULTIPLY", "DIVIDE", "CONCAT", "ASSIGN", 
			"PIPE", "ARROW", "PLACEHOLDER", "WILDCARD", "ELLIPSIS", "LPAREN", "RPAREN", 
			"LBRACE", "RBRACE", "LBRACKET", "RBRACKET", "PIPE_DELIM", "COLON", "COMMA", 
			"DOT", "Integer", "Float", "String", "Boolean", "Identifier", "Comment", 
			"WS", "NEWLINE"
		};
	}
	private static final String[] _SYMBOLIC_NAMES = makeSymbolicNames();
	public static final Vocabulary VOCABULARY = new VocabularyImpl(_LITERAL_NAMES, _SYMBOLIC_NAMES);

	/**
	 * @deprecated Use {@link #VOCABULARY} instead.
	 */
	@Deprecated
	public static final String[] tokenNames;
	static {
		tokenNames = new String[_SYMBOLIC_NAMES.length];
		for (int i = 0; i < tokenNames.length; i++) {
			tokenNames[i] = VOCABULARY.getLiteralName(i);
			if (tokenNames[i] == null) {
				tokenNames[i] = VOCABULARY.getSymbolicName(i);
			}

			if (tokenNames[i] == null) {
				tokenNames[i] = "<INVALID>";
			}
		}
	}

	@Override
	@Deprecated
	public String[] getTokenNames() {
		return tokenNames;
	}

	@Override

	public Vocabulary getVocabulary() {
		return VOCABULARY;
	}

	@Override
	public String getGrammarFileName() { return "Funk.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public FunkParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	public static class ProgramContext extends ParserRuleContext {
		public TerminalNode EOF() { return getToken(FunkParser.EOF, 0); }
		public List<StatementContext> statement() {
			return getRuleContexts(StatementContext.class);
		}
		public StatementContext statement(int i) {
			return getRuleContext(StatementContext.class,i);
		}
		public List<TerminalNode> NEWLINE() { return getTokens(FunkParser.NEWLINE); }
		public TerminalNode NEWLINE(int i) {
			return getToken(FunkParser.NEWLINE, i);
		}
		public ProgramContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_program; }
	}

	public final ProgramContext program() throws RecognitionException {
		ProgramContext _localctx = new ProgramContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_program);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(98);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << LET) | (1L << TYPE) | (1L << IF) | (1L << MATCH) | (1L << HANDLE) | (1L << RESUME) | (1L << EFFECT) | (1L << ABILITY) | (1L << MODULE) | (1L << EXPOSE) | (1L << ACCESS) | (1L << DO) | (1L << PLACEHOLDER) | (1L << LPAREN) | (1L << LBRACE) | (1L << Integer) | (1L << Float) | (1L << String) | (1L << Boolean) | (1L << Identifier) | (1L << NEWLINE))) != 0)) {
				{
				setState(96);
				_errHandler.sync(this);
				switch (_input.LA(1)) {
				case LET:
				case TYPE:
				case IF:
				case MATCH:
				case HANDLE:
				case RESUME:
				case EFFECT:
				case ABILITY:
				case MODULE:
				case EXPOSE:
				case ACCESS:
				case DO:
				case PLACEHOLDER:
				case LPAREN:
				case LBRACE:
				case Integer:
				case Float:
				case String:
				case Boolean:
				case Identifier:
					{
					setState(94);
					statement();
					}
					break;
				case NEWLINE:
					{
					setState(95);
					match(NEWLINE);
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				}
				setState(100);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(101);
			match(EOF);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class StatementContext extends ParserRuleContext {
		public LetBindingContext letBinding() {
			return getRuleContext(LetBindingContext.class,0);
		}
		public TypeDeclarationContext typeDeclaration() {
			return getRuleContext(TypeDeclarationContext.class,0);
		}
		public EffectDeclarationContext effectDeclaration() {
			return getRuleContext(EffectDeclarationContext.class,0);
		}
		public AbilityDeclarationContext abilityDeclaration() {
			return getRuleContext(AbilityDeclarationContext.class,0);
		}
		public ModuleDeclarationContext moduleDeclaration() {
			return getRuleContext(ModuleDeclarationContext.class,0);
		}
		public ExposeDeclarationContext exposeDeclaration() {
			return getRuleContext(ExposeDeclarationContext.class,0);
		}
		public AccessDeclarationContext accessDeclaration() {
			return getRuleContext(AccessDeclarationContext.class,0);
		}
		public ResumeStatementContext resumeStatement() {
			return getRuleContext(ResumeStatementContext.class,0);
		}
		public ExpressionStatementContext expressionStatement() {
			return getRuleContext(ExpressionStatementContext.class,0);
		}
		public StatementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_statement; }
	}

	public final StatementContext statement() throws RecognitionException {
		StatementContext _localctx = new StatementContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_statement);
		try {
			setState(112);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case LET:
				enterOuterAlt(_localctx, 1);
				{
				setState(103);
				letBinding();
				}
				break;
			case TYPE:
				enterOuterAlt(_localctx, 2);
				{
				setState(104);
				typeDeclaration();
				}
				break;
			case EFFECT:
				enterOuterAlt(_localctx, 3);
				{
				setState(105);
				effectDeclaration();
				}
				break;
			case ABILITY:
				enterOuterAlt(_localctx, 4);
				{
				setState(106);
				abilityDeclaration();
				}
				break;
			case MODULE:
				enterOuterAlt(_localctx, 5);
				{
				setState(107);
				moduleDeclaration();
				}
				break;
			case EXPOSE:
				enterOuterAlt(_localctx, 6);
				{
				setState(108);
				exposeDeclaration();
				}
				break;
			case ACCESS:
				enterOuterAlt(_localctx, 7);
				{
				setState(109);
				accessDeclaration();
				}
				break;
			case RESUME:
				enterOuterAlt(_localctx, 8);
				{
				setState(110);
				resumeStatement();
				}
				break;
			case IF:
			case MATCH:
			case HANDLE:
			case DO:
			case PLACEHOLDER:
			case LPAREN:
			case LBRACE:
			case Integer:
			case Float:
			case String:
			case Boolean:
			case Identifier:
				enterOuterAlt(_localctx, 9);
				{
				setState(111);
				expressionStatement();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class ModuleDeclarationContext extends ParserRuleContext {
		public TerminalNode MODULE() { return getToken(FunkParser.MODULE, 0); }
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode WHERE() { return getToken(FunkParser.WHERE, 0); }
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public ModuleDeclarationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_moduleDeclaration; }
	}

	public final ModuleDeclarationContext moduleDeclaration() throws RecognitionException {
		ModuleDeclarationContext _localctx = new ModuleDeclarationContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_moduleDeclaration);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(114);
			match(MODULE);
			setState(115);
			match(Identifier);
			setState(116);
			match(WHERE);
			setState(117);
			match(NEWLINE);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class ExposeDeclarationContext extends ParserRuleContext {
		public TerminalNode EXPOSE() { return getToken(FunkParser.EXPOSE, 0); }
		public ExposeListContext exposeList() {
			return getRuleContext(ExposeListContext.class,0);
		}
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public ExposeDeclarationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_exposeDeclaration; }
	}

	public final ExposeDeclarationContext exposeDeclaration() throws RecognitionException {
		ExposeDeclarationContext _localctx = new ExposeDeclarationContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_exposeDeclaration);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(119);
			match(EXPOSE);
			setState(120);
			exposeList();
			setState(122);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,3,_ctx) ) {
			case 1:
				{
				setState(121);
				match(NEWLINE);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class ExposeListContext extends ParserRuleContext {
		public List<ExposeItemContext> exposeItem() {
			return getRuleContexts(ExposeItemContext.class);
		}
		public ExposeItemContext exposeItem(int i) {
			return getRuleContext(ExposeItemContext.class,i);
		}
		public List<TerminalNode> COMMA() { return getTokens(FunkParser.COMMA); }
		public TerminalNode COMMA(int i) {
			return getToken(FunkParser.COMMA, i);
		}
		public ExposeListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_exposeList; }
	}

	public final ExposeListContext exposeList() throws RecognitionException {
		ExposeListContext _localctx = new ExposeListContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_exposeList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(124);
			exposeItem();
			setState(129);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==COMMA) {
				{
				{
				setState(125);
				match(COMMA);
				setState(126);
				exposeItem();
				}
				}
				setState(131);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class ExposeItemContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode TYPE() { return getToken(FunkParser.TYPE, 0); }
		public TerminalNode EFFECT() { return getToken(FunkParser.EFFECT, 0); }
		public TerminalNode ABILITY() { return getToken(FunkParser.ABILITY, 0); }
		public ExposeItemContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_exposeItem; }
	}

	public final ExposeItemContext exposeItem() throws RecognitionException {
		ExposeItemContext _localctx = new ExposeItemContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_exposeItem);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(133);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << TYPE) | (1L << EFFECT) | (1L << ABILITY))) != 0)) {
				{
				setState(132);
				_la = _input.LA(1);
				if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << TYPE) | (1L << EFFECT) | (1L << ABILITY))) != 0)) ) {
				_errHandler.recoverInline(this);
				}
				else {
					if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
					_errHandler.reportMatch(this);
					consume();
				}
				}
			}

			setState(135);
			match(Identifier);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class AccessDeclarationContext extends ParserRuleContext {
		public TerminalNode ACCESS() { return getToken(FunkParser.ACCESS, 0); }
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode DOT() { return getToken(FunkParser.DOT, 0); }
		public TerminalNode LBRACE() { return getToken(FunkParser.LBRACE, 0); }
		public AccessListContext accessList() {
			return getRuleContext(AccessListContext.class,0);
		}
		public TerminalNode RBRACE() { return getToken(FunkParser.RBRACE, 0); }
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public AccessDeclarationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_accessDeclaration; }
	}

	public final AccessDeclarationContext accessDeclaration() throws RecognitionException {
		AccessDeclarationContext _localctx = new AccessDeclarationContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_accessDeclaration);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(137);
			match(ACCESS);
			setState(138);
			match(Identifier);
			setState(139);
			match(DOT);
			setState(140);
			match(LBRACE);
			setState(141);
			accessList();
			setState(142);
			match(RBRACE);
			setState(144);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,6,_ctx) ) {
			case 1:
				{
				setState(143);
				match(NEWLINE);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class AccessListContext extends ParserRuleContext {
		public List<AccessItemContext> accessItem() {
			return getRuleContexts(AccessItemContext.class);
		}
		public AccessItemContext accessItem(int i) {
			return getRuleContext(AccessItemContext.class,i);
		}
		public List<TerminalNode> COMMA() { return getTokens(FunkParser.COMMA); }
		public TerminalNode COMMA(int i) {
			return getToken(FunkParser.COMMA, i);
		}
		public AccessListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_accessList; }
	}

	public final AccessListContext accessList() throws RecognitionException {
		AccessListContext _localctx = new AccessListContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_accessList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(146);
			accessItem();
			setState(151);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==COMMA) {
				{
				{
				setState(147);
				match(COMMA);
				setState(148);
				accessItem();
				}
				}
				setState(153);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class AccessItemContext extends ParserRuleContext {
		public List<TerminalNode> Identifier() { return getTokens(FunkParser.Identifier); }
		public TerminalNode Identifier(int i) {
			return getToken(FunkParser.Identifier, i);
		}
		public TerminalNode AS() { return getToken(FunkParser.AS, 0); }
		public AccessItemContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_accessItem; }
	}

	public final AccessItemContext accessItem() throws RecognitionException {
		AccessItemContext _localctx = new AccessItemContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_accessItem);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(154);
			match(Identifier);
			setState(157);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==AS) {
				{
				setState(155);
				match(AS);
				setState(156);
				match(Identifier);
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class LetBindingContext extends ParserRuleContext {
		public TerminalNode LET() { return getToken(FunkParser.LET, 0); }
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode ASSIGN() { return getToken(FunkParser.ASSIGN, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode COLON() { return getToken(FunkParser.COLON, 0); }
		public TypeAnnotationContext typeAnnotation() {
			return getRuleContext(TypeAnnotationContext.class,0);
		}
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public LetBindingContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_letBinding; }
	}

	public final LetBindingContext letBinding() throws RecognitionException {
		LetBindingContext _localctx = new LetBindingContext(_ctx, getState());
		enterRule(_localctx, 18, RULE_letBinding);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(159);
			match(LET);
			setState(160);
			match(Identifier);
			setState(163);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==COLON) {
				{
				setState(161);
				match(COLON);
				setState(162);
				typeAnnotation(0);
				}
			}

			setState(165);
			match(ASSIGN);
			setState(166);
			expression();
			setState(168);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,10,_ctx) ) {
			case 1:
				{
				setState(167);
				match(NEWLINE);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class TypeDeclarationContext extends ParserRuleContext {
		public TerminalNode TYPE() { return getToken(FunkParser.TYPE, 0); }
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode ASSIGN() { return getToken(FunkParser.ASSIGN, 0); }
		public TypeDefinitionContext typeDefinition() {
			return getRuleContext(TypeDefinitionContext.class,0);
		}
		public TypeParamsContext typeParams() {
			return getRuleContext(TypeParamsContext.class,0);
		}
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public TypeDeclarationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_typeDeclaration; }
	}

	public final TypeDeclarationContext typeDeclaration() throws RecognitionException {
		TypeDeclarationContext _localctx = new TypeDeclarationContext(_ctx, getState());
		enterRule(_localctx, 20, RULE_typeDeclaration);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(170);
			match(TYPE);
			setState(171);
			match(Identifier);
			setState(173);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==Identifier) {
				{
				setState(172);
				typeParams();
				}
			}

			setState(175);
			match(ASSIGN);
			setState(176);
			typeDefinition();
			setState(178);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,12,_ctx) ) {
			case 1:
				{
				setState(177);
				match(NEWLINE);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class TypeParamsContext extends ParserRuleContext {
		public List<TerminalNode> Identifier() { return getTokens(FunkParser.Identifier); }
		public TerminalNode Identifier(int i) {
			return getToken(FunkParser.Identifier, i);
		}
		public TypeParamsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_typeParams; }
	}

	public final TypeParamsContext typeParams() throws RecognitionException {
		TypeParamsContext _localctx = new TypeParamsContext(_ctx, getState());
		enterRule(_localctx, 22, RULE_typeParams);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(181); 
			_errHandler.sync(this);
			_la = _input.LA(1);
			do {
				{
				{
				setState(180);
				match(Identifier);
				}
				}
				setState(183); 
				_errHandler.sync(this);
				_la = _input.LA(1);
			} while ( _la==Identifier );
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class TypeDefinitionContext extends ParserRuleContext {
		public RecordTypeContext recordType() {
			return getRuleContext(RecordTypeContext.class,0);
		}
		public VariantTypeContext variantType() {
			return getRuleContext(VariantTypeContext.class,0);
		}
		public TypeDefinitionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_typeDefinition; }
	}

	public final TypeDefinitionContext typeDefinition() throws RecognitionException {
		TypeDefinitionContext _localctx = new TypeDefinitionContext(_ctx, getState());
		enterRule(_localctx, 24, RULE_typeDefinition);
		try {
			setState(187);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case LBRACE:
				enterOuterAlt(_localctx, 1);
				{
				setState(185);
				recordType();
				}
				break;
			case PIPE_DELIM:
				enterOuterAlt(_localctx, 2);
				{
				setState(186);
				variantType();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class VariantTypeContext extends ParserRuleContext {
		public List<TerminalNode> PIPE_DELIM() { return getTokens(FunkParser.PIPE_DELIM); }
		public TerminalNode PIPE_DELIM(int i) {
			return getToken(FunkParser.PIPE_DELIM, i);
		}
		public List<VariantConstructorContext> variantConstructor() {
			return getRuleContexts(VariantConstructorContext.class);
		}
		public VariantConstructorContext variantConstructor(int i) {
			return getRuleContext(VariantConstructorContext.class,i);
		}
		public List<TerminalNode> COMMA() { return getTokens(FunkParser.COMMA); }
		public TerminalNode COMMA(int i) {
			return getToken(FunkParser.COMMA, i);
		}
		public TerminalNode ELLIPSIS() { return getToken(FunkParser.ELLIPSIS, 0); }
		public VariantTypeContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_variantType; }
	}

	public final VariantTypeContext variantType() throws RecognitionException {
		VariantTypeContext _localctx = new VariantTypeContext(_ctx, getState());
		enterRule(_localctx, 26, RULE_variantType);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(189);
			match(PIPE_DELIM);
			setState(190);
			variantConstructor();
			setState(195);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,15,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(191);
					match(COMMA);
					setState(192);
					variantConstructor();
					}
					} 
				}
				setState(197);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,15,_ctx);
			}
			setState(200);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==COMMA) {
				{
				setState(198);
				match(COMMA);
				setState(199);
				match(ELLIPSIS);
				}
			}

			setState(202);
			match(PIPE_DELIM);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class VariantConstructorContext extends ParserRuleContext {
		public QualifiedIdentifierContext qualifiedIdentifier() {
			return getRuleContext(QualifiedIdentifierContext.class,0);
		}
		public RecordTypeContext recordType() {
			return getRuleContext(RecordTypeContext.class,0);
		}
		public VariantConstructorContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_variantConstructor; }
	}

	public final VariantConstructorContext variantConstructor() throws RecognitionException {
		VariantConstructorContext _localctx = new VariantConstructorContext(_ctx, getState());
		enterRule(_localctx, 28, RULE_variantConstructor);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(204);
			qualifiedIdentifier();
			setState(206);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==LBRACE) {
				{
				setState(205);
				recordType();
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class QualifiedIdentifierContext extends ParserRuleContext {
		public List<TerminalNode> Identifier() { return getTokens(FunkParser.Identifier); }
		public TerminalNode Identifier(int i) {
			return getToken(FunkParser.Identifier, i);
		}
		public List<TerminalNode> DOT() { return getTokens(FunkParser.DOT); }
		public TerminalNode DOT(int i) {
			return getToken(FunkParser.DOT, i);
		}
		public QualifiedIdentifierContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_qualifiedIdentifier; }
	}

	public final QualifiedIdentifierContext qualifiedIdentifier() throws RecognitionException {
		QualifiedIdentifierContext _localctx = new QualifiedIdentifierContext(_ctx, getState());
		enterRule(_localctx, 30, RULE_qualifiedIdentifier);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(208);
			match(Identifier);
			setState(213);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==DOT) {
				{
				{
				setState(209);
				match(DOT);
				setState(210);
				match(Identifier);
				}
				}
				setState(215);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class EffectDeclarationContext extends ParserRuleContext {
		public TerminalNode EFFECT() { return getToken(FunkParser.EFFECT, 0); }
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode COLON() { return getToken(FunkParser.COLON, 0); }
		public RecordTypeContext recordType() {
			return getRuleContext(RecordTypeContext.class,0);
		}
		public TerminalNode ARROW() { return getToken(FunkParser.ARROW, 0); }
		public VariantTypeContext variantType() {
			return getRuleContext(VariantTypeContext.class,0);
		}
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public EffectDeclarationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_effectDeclaration; }
	}

	public final EffectDeclarationContext effectDeclaration() throws RecognitionException {
		EffectDeclarationContext _localctx = new EffectDeclarationContext(_ctx, getState());
		enterRule(_localctx, 32, RULE_effectDeclaration);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(216);
			match(EFFECT);
			setState(217);
			match(Identifier);
			setState(218);
			match(COLON);
			setState(219);
			recordType();
			setState(220);
			match(ARROW);
			setState(221);
			variantType();
			setState(223);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,19,_ctx) ) {
			case 1:
				{
				setState(222);
				match(NEWLINE);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class AbilityDeclarationContext extends ParserRuleContext {
		public TerminalNode ABILITY() { return getToken(FunkParser.ABILITY, 0); }
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode WHERE() { return getToken(FunkParser.WHERE, 0); }
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public List<EffectSignatureContext> effectSignature() {
			return getRuleContexts(EffectSignatureContext.class);
		}
		public EffectSignatureContext effectSignature(int i) {
			return getRuleContext(EffectSignatureContext.class,i);
		}
		public AbilityDeclarationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_abilityDeclaration; }
	}

	public final AbilityDeclarationContext abilityDeclaration() throws RecognitionException {
		AbilityDeclarationContext _localctx = new AbilityDeclarationContext(_ctx, getState());
		enterRule(_localctx, 34, RULE_abilityDeclaration);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(225);
			match(ABILITY);
			setState(226);
			match(Identifier);
			setState(227);
			match(WHERE);
			setState(228);
			match(NEWLINE);
			setState(232);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,20,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(229);
					effectSignature();
					}
					} 
				}
				setState(234);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,20,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class EffectSignatureContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode COLON() { return getToken(FunkParser.COLON, 0); }
		public RecordTypeContext recordType() {
			return getRuleContext(RecordTypeContext.class,0);
		}
		public TerminalNode ARROW() { return getToken(FunkParser.ARROW, 0); }
		public VariantTypeContext variantType() {
			return getRuleContext(VariantTypeContext.class,0);
		}
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public EffectSignatureContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_effectSignature; }
	}

	public final EffectSignatureContext effectSignature() throws RecognitionException {
		EffectSignatureContext _localctx = new EffectSignatureContext(_ctx, getState());
		enterRule(_localctx, 36, RULE_effectSignature);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(235);
			match(Identifier);
			setState(236);
			match(COLON);
			setState(237);
			recordType();
			setState(238);
			match(ARROW);
			setState(239);
			variantType();
			setState(241);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,21,_ctx) ) {
			case 1:
				{
				setState(240);
				match(NEWLINE);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class ResumeStatementContext extends ParserRuleContext {
		public TerminalNode RESUME() { return getToken(FunkParser.RESUME, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public ResumeStatementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_resumeStatement; }
	}

	public final ResumeStatementContext resumeStatement() throws RecognitionException {
		ResumeStatementContext _localctx = new ResumeStatementContext(_ctx, getState());
		enterRule(_localctx, 38, RULE_resumeStatement);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(243);
			match(RESUME);
			setState(244);
			expression();
			setState(246);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,22,_ctx) ) {
			case 1:
				{
				setState(245);
				match(NEWLINE);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class ExpressionStatementContext extends ParserRuleContext {
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public ExpressionStatementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_expressionStatement; }
	}

	public final ExpressionStatementContext expressionStatement() throws RecognitionException {
		ExpressionStatementContext _localctx = new ExpressionStatementContext(_ctx, getState());
		enterRule(_localctx, 40, RULE_expressionStatement);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(248);
			expression();
			setState(250);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,23,_ctx) ) {
			case 1:
				{
				setState(249);
				match(NEWLINE);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class ExpressionContext extends ParserRuleContext {
		public PipeExpressionContext pipeExpression() {
			return getRuleContext(PipeExpressionContext.class,0);
		}
		public ExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_expression; }
	}

	public final ExpressionContext expression() throws RecognitionException {
		ExpressionContext _localctx = new ExpressionContext(_ctx, getState());
		enterRule(_localctx, 42, RULE_expression);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(252);
			pipeExpression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class PipeExpressionContext extends ParserRuleContext {
		public List<MatchExpressionContext> matchExpression() {
			return getRuleContexts(MatchExpressionContext.class);
		}
		public MatchExpressionContext matchExpression(int i) {
			return getRuleContext(MatchExpressionContext.class,i);
		}
		public List<TerminalNode> PIPE() { return getTokens(FunkParser.PIPE); }
		public TerminalNode PIPE(int i) {
			return getToken(FunkParser.PIPE, i);
		}
		public PipeExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_pipeExpression; }
	}

	public final PipeExpressionContext pipeExpression() throws RecognitionException {
		PipeExpressionContext _localctx = new PipeExpressionContext(_ctx, getState());
		enterRule(_localctx, 44, RULE_pipeExpression);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(254);
			matchExpression();
			setState(259);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,24,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(255);
					match(PIPE);
					setState(256);
					matchExpression();
					}
					} 
				}
				setState(261);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,24,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class MatchExpressionContext extends ParserRuleContext {
		public TerminalNode MATCH() { return getToken(FunkParser.MATCH, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode LBRACE() { return getToken(FunkParser.LBRACE, 0); }
		public TerminalNode RBRACE() { return getToken(FunkParser.RBRACE, 0); }
		public List<MatchCaseContext> matchCase() {
			return getRuleContexts(MatchCaseContext.class);
		}
		public MatchCaseContext matchCase(int i) {
			return getRuleContext(MatchCaseContext.class,i);
		}
		public HandleExpressionContext handleExpression() {
			return getRuleContext(HandleExpressionContext.class,0);
		}
		public MatchExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_matchExpression; }
	}

	public final MatchExpressionContext matchExpression() throws RecognitionException {
		MatchExpressionContext _localctx = new MatchExpressionContext(_ctx, getState());
		enterRule(_localctx, 46, RULE_matchExpression);
		int _la;
		try {
			setState(274);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case MATCH:
				enterOuterAlt(_localctx, 1);
				{
				setState(262);
				match(MATCH);
				setState(263);
				expression();
				setState(264);
				match(LBRACE);
				setState(268);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << WILDCARD) | (1L << LBRACE) | (1L << Identifier))) != 0)) {
					{
					{
					setState(265);
					matchCase();
					}
					}
					setState(270);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				setState(271);
				match(RBRACE);
				}
				break;
			case IF:
			case HANDLE:
			case DO:
			case PLACEHOLDER:
			case LPAREN:
			case LBRACE:
			case Integer:
			case Float:
			case String:
			case Boolean:
			case Identifier:
				enterOuterAlt(_localctx, 2);
				{
				setState(273);
				handleExpression();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class MatchCaseContext extends ParserRuleContext {
		public PatternContext pattern() {
			return getRuleContext(PatternContext.class,0);
		}
		public TerminalNode ARROW() { return getToken(FunkParser.ARROW, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public MatchCaseContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_matchCase; }
	}

	public final MatchCaseContext matchCase() throws RecognitionException {
		MatchCaseContext _localctx = new MatchCaseContext(_ctx, getState());
		enterRule(_localctx, 48, RULE_matchCase);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(276);
			pattern();
			setState(277);
			match(ARROW);
			setState(278);
			expression();
			setState(280);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==NEWLINE) {
				{
				setState(279);
				match(NEWLINE);
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class PatternContext extends ParserRuleContext {
		public TerminalNode WILDCARD() { return getToken(FunkParser.WILDCARD, 0); }
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public RecordPatternContext recordPattern() {
			return getRuleContext(RecordPatternContext.class,0);
		}
		public PatternContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_pattern; }
	}

	public final PatternContext pattern() throws RecognitionException {
		PatternContext _localctx = new PatternContext(_ctx, getState());
		enterRule(_localctx, 50, RULE_pattern);
		int _la;
		try {
			setState(289);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,29,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(282);
				match(WILDCARD);
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(283);
				match(Identifier);
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(284);
				match(Identifier);
				setState(286);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==LBRACE) {
					{
					setState(285);
					recordPattern();
					}
				}

				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(288);
				recordPattern();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class RecordPatternContext extends ParserRuleContext {
		public TerminalNode LBRACE() { return getToken(FunkParser.LBRACE, 0); }
		public TerminalNode RBRACE() { return getToken(FunkParser.RBRACE, 0); }
		public List<RecordPatternEntryContext> recordPatternEntry() {
			return getRuleContexts(RecordPatternEntryContext.class);
		}
		public RecordPatternEntryContext recordPatternEntry(int i) {
			return getRuleContext(RecordPatternEntryContext.class,i);
		}
		public List<TerminalNode> COMMA() { return getTokens(FunkParser.COMMA); }
		public TerminalNode COMMA(int i) {
			return getToken(FunkParser.COMMA, i);
		}
		public RecordPatternContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_recordPattern; }
	}

	public final RecordPatternContext recordPattern() throws RecognitionException {
		RecordPatternContext _localctx = new RecordPatternContext(_ctx, getState());
		enterRule(_localctx, 52, RULE_recordPattern);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(291);
			match(LBRACE);
			setState(300);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==Identifier) {
				{
				setState(292);
				recordPatternEntry();
				setState(297);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==COMMA) {
					{
					{
					setState(293);
					match(COMMA);
					setState(294);
					recordPatternEntry();
					}
					}
					setState(299);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				}
			}

			setState(302);
			match(RBRACE);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class RecordPatternEntryContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode ASSIGN() { return getToken(FunkParser.ASSIGN, 0); }
		public PatternContext pattern() {
			return getRuleContext(PatternContext.class,0);
		}
		public RecordPatternEntryContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_recordPatternEntry; }
	}

	public final RecordPatternEntryContext recordPatternEntry() throws RecognitionException {
		RecordPatternEntryContext _localctx = new RecordPatternEntryContext(_ctx, getState());
		enterRule(_localctx, 54, RULE_recordPatternEntry);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(304);
			match(Identifier);
			setState(307);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ASSIGN) {
				{
				setState(305);
				match(ASSIGN);
				setState(306);
				pattern();
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class HandleExpressionContext extends ParserRuleContext {
		public TerminalNode HANDLE() { return getToken(FunkParser.HANDLE, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode LBRACE() { return getToken(FunkParser.LBRACE, 0); }
		public TerminalNode RBRACE() { return getToken(FunkParser.RBRACE, 0); }
		public List<HandleCaseContext> handleCase() {
			return getRuleContexts(HandleCaseContext.class);
		}
		public HandleCaseContext handleCase(int i) {
			return getRuleContext(HandleCaseContext.class,i);
		}
		public TerminalNode WITH() { return getToken(FunkParser.WITH, 0); }
		public RecordLiteralContext recordLiteral() {
			return getRuleContext(RecordLiteralContext.class,0);
		}
		public IfExpressionContext ifExpression() {
			return getRuleContext(IfExpressionContext.class,0);
		}
		public HandleExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_handleExpression; }
	}

	public final HandleExpressionContext handleExpression() throws RecognitionException {
		HandleExpressionContext _localctx = new HandleExpressionContext(_ctx, getState());
		enterRule(_localctx, 56, RULE_handleExpression);
		int _la;
		try {
			setState(324);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case HANDLE:
				enterOuterAlt(_localctx, 1);
				{
				setState(309);
				match(HANDLE);
				setState(310);
				expression();
				setState(311);
				match(LBRACE);
				setState(315);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==Identifier) {
					{
					{
					setState(312);
					handleCase();
					}
					}
					setState(317);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				setState(318);
				match(RBRACE);
				setState(321);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,34,_ctx) ) {
				case 1:
					{
					setState(319);
					match(WITH);
					setState(320);
					recordLiteral();
					}
					break;
				}
				}
				break;
			case IF:
			case DO:
			case PLACEHOLDER:
			case LPAREN:
			case LBRACE:
			case Integer:
			case Float:
			case String:
			case Boolean:
			case Identifier:
				enterOuterAlt(_localctx, 2);
				{
				setState(323);
				ifExpression();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class HandleCaseContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode ARROW() { return getToken(FunkParser.ARROW, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public RecordPatternContext recordPattern() {
			return getRuleContext(RecordPatternContext.class,0);
		}
		public TerminalNode NEWLINE() { return getToken(FunkParser.NEWLINE, 0); }
		public HandleCaseContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_handleCase; }
	}

	public final HandleCaseContext handleCase() throws RecognitionException {
		HandleCaseContext _localctx = new HandleCaseContext(_ctx, getState());
		enterRule(_localctx, 58, RULE_handleCase);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(326);
			match(Identifier);
			setState(328);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==LBRACE) {
				{
				setState(327);
				recordPattern();
				}
			}

			setState(330);
			match(ARROW);
			setState(331);
			expression();
			setState(333);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==NEWLINE) {
				{
				setState(332);
				match(NEWLINE);
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class IfExpressionContext extends ParserRuleContext {
		public TerminalNode IF() { return getToken(FunkParser.IF, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode THEN() { return getToken(FunkParser.THEN, 0); }
		public TerminalNode ELSE() { return getToken(FunkParser.ELSE, 0); }
		public UpdateExpressionContext updateExpression() {
			return getRuleContext(UpdateExpressionContext.class,0);
		}
		public IfExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_ifExpression; }
	}

	public final IfExpressionContext ifExpression() throws RecognitionException {
		IfExpressionContext _localctx = new IfExpressionContext(_ctx, getState());
		enterRule(_localctx, 60, RULE_ifExpression);
		try {
			setState(343);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case IF:
				enterOuterAlt(_localctx, 1);
				{
				setState(335);
				match(IF);
				setState(336);
				expression();
				setState(337);
				match(THEN);
				setState(338);
				expression();
				setState(339);
				match(ELSE);
				setState(340);
				expression();
				}
				break;
			case DO:
			case PLACEHOLDER:
			case LPAREN:
			case LBRACE:
			case Integer:
			case Float:
			case String:
			case Boolean:
			case Identifier:
				enterOuterAlt(_localctx, 2);
				{
				setState(342);
				updateExpression();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class UpdateExpressionContext extends ParserRuleContext {
		public AdditiveExpressionContext additiveExpression() {
			return getRuleContext(AdditiveExpressionContext.class,0);
		}
		public TerminalNode WITH() { return getToken(FunkParser.WITH, 0); }
		public RecordLiteralContext recordLiteral() {
			return getRuleContext(RecordLiteralContext.class,0);
		}
		public UpdateExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_updateExpression; }
	}

	public final UpdateExpressionContext updateExpression() throws RecognitionException {
		UpdateExpressionContext _localctx = new UpdateExpressionContext(_ctx, getState());
		enterRule(_localctx, 62, RULE_updateExpression);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(345);
			additiveExpression();
			setState(348);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,39,_ctx) ) {
			case 1:
				{
				setState(346);
				match(WITH);
				setState(347);
				recordLiteral();
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class AdditiveExpressionContext extends ParserRuleContext {
		public List<MultiplicativeExpressionContext> multiplicativeExpression() {
			return getRuleContexts(MultiplicativeExpressionContext.class);
		}
		public MultiplicativeExpressionContext multiplicativeExpression(int i) {
			return getRuleContext(MultiplicativeExpressionContext.class,i);
		}
		public List<TerminalNode> PLUS() { return getTokens(FunkParser.PLUS); }
		public TerminalNode PLUS(int i) {
			return getToken(FunkParser.PLUS, i);
		}
		public List<TerminalNode> MINUS() { return getTokens(FunkParser.MINUS); }
		public TerminalNode MINUS(int i) {
			return getToken(FunkParser.MINUS, i);
		}
		public List<TerminalNode> CONCAT() { return getTokens(FunkParser.CONCAT); }
		public TerminalNode CONCAT(int i) {
			return getToken(FunkParser.CONCAT, i);
		}
		public AdditiveExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_additiveExpression; }
	}

	public final AdditiveExpressionContext additiveExpression() throws RecognitionException {
		AdditiveExpressionContext _localctx = new AdditiveExpressionContext(_ctx, getState());
		enterRule(_localctx, 64, RULE_additiveExpression);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(350);
			multiplicativeExpression();
			setState(355);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,40,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(351);
					_la = _input.LA(1);
					if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << PLUS) | (1L << MINUS) | (1L << CONCAT))) != 0)) ) {
					_errHandler.recoverInline(this);
					}
					else {
						if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
						_errHandler.reportMatch(this);
						consume();
					}
					setState(352);
					multiplicativeExpression();
					}
					} 
				}
				setState(357);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,40,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class MultiplicativeExpressionContext extends ParserRuleContext {
		public List<MemberAccessExpressionContext> memberAccessExpression() {
			return getRuleContexts(MemberAccessExpressionContext.class);
		}
		public MemberAccessExpressionContext memberAccessExpression(int i) {
			return getRuleContext(MemberAccessExpressionContext.class,i);
		}
		public List<TerminalNode> MULTIPLY() { return getTokens(FunkParser.MULTIPLY); }
		public TerminalNode MULTIPLY(int i) {
			return getToken(FunkParser.MULTIPLY, i);
		}
		public List<TerminalNode> DIVIDE() { return getTokens(FunkParser.DIVIDE); }
		public TerminalNode DIVIDE(int i) {
			return getToken(FunkParser.DIVIDE, i);
		}
		public MultiplicativeExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_multiplicativeExpression; }
	}

	public final MultiplicativeExpressionContext multiplicativeExpression() throws RecognitionException {
		MultiplicativeExpressionContext _localctx = new MultiplicativeExpressionContext(_ctx, getState());
		enterRule(_localctx, 66, RULE_multiplicativeExpression);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(358);
			memberAccessExpression();
			setState(363);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,41,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(359);
					_la = _input.LA(1);
					if ( !(_la==MULTIPLY || _la==DIVIDE) ) {
					_errHandler.recoverInline(this);
					}
					else {
						if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
						_errHandler.reportMatch(this);
						consume();
					}
					setState(360);
					memberAccessExpression();
					}
					} 
				}
				setState(365);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,41,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class MemberAccessExpressionContext extends ParserRuleContext {
		public PrimaryExpressionContext primaryExpression() {
			return getRuleContext(PrimaryExpressionContext.class,0);
		}
		public List<TerminalNode> DOT() { return getTokens(FunkParser.DOT); }
		public TerminalNode DOT(int i) {
			return getToken(FunkParser.DOT, i);
		}
		public List<TerminalNode> Identifier() { return getTokens(FunkParser.Identifier); }
		public TerminalNode Identifier(int i) {
			return getToken(FunkParser.Identifier, i);
		}
		public MemberAccessExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_memberAccessExpression; }
	}

	public final MemberAccessExpressionContext memberAccessExpression() throws RecognitionException {
		MemberAccessExpressionContext _localctx = new MemberAccessExpressionContext(_ctx, getState());
		enterRule(_localctx, 68, RULE_memberAccessExpression);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(366);
			primaryExpression(0);
			setState(371);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,42,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(367);
					match(DOT);
					setState(368);
					match(Identifier);
					}
					} 
				}
				setState(373);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,42,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class PrimaryExpressionContext extends ParserRuleContext {
		public LiteralContext literal() {
			return getRuleContext(LiteralContext.class,0);
		}
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode PLACEHOLDER() { return getToken(FunkParser.PLACEHOLDER, 0); }
		public TerminalNode LPAREN() { return getToken(FunkParser.LPAREN, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode RPAREN() { return getToken(FunkParser.RPAREN, 0); }
		public RecordLiteralContext recordLiteral() {
			return getRuleContext(RecordLiteralContext.class,0);
		}
		public BlockExpressionContext blockExpression() {
			return getRuleContext(BlockExpressionContext.class,0);
		}
		public DoExpressionContext doExpression() {
			return getRuleContext(DoExpressionContext.class,0);
		}
		public LambdaExpressionContext lambdaExpression() {
			return getRuleContext(LambdaExpressionContext.class,0);
		}
		public PrimaryExpressionContext primaryExpression() {
			return getRuleContext(PrimaryExpressionContext.class,0);
		}
		public PrimaryExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_primaryExpression; }
	}

	public final PrimaryExpressionContext primaryExpression() throws RecognitionException {
		return primaryExpression(0);
	}

	private PrimaryExpressionContext primaryExpression(int _p) throws RecognitionException {
		ParserRuleContext _parentctx = _ctx;
		int _parentState = getState();
		PrimaryExpressionContext _localctx = new PrimaryExpressionContext(_ctx, _parentState);
		PrimaryExpressionContext _prevctx = _localctx;
		int _startState = 70;
		enterRecursionRule(_localctx, 70, RULE_primaryExpression, _p);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(386);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,43,_ctx) ) {
			case 1:
				{
				setState(375);
				literal();
				}
				break;
			case 2:
				{
				setState(376);
				match(Identifier);
				}
				break;
			case 3:
				{
				setState(377);
				match(PLACEHOLDER);
				}
				break;
			case 4:
				{
				setState(378);
				match(LPAREN);
				setState(379);
				expression();
				setState(380);
				match(RPAREN);
				}
				break;
			case 5:
				{
				setState(382);
				recordLiteral();
				}
				break;
			case 6:
				{
				setState(383);
				blockExpression();
				}
				break;
			case 7:
				{
				setState(384);
				doExpression();
				}
				break;
			case 8:
				{
				setState(385);
				lambdaExpression();
				}
				break;
			}
			_ctx.stop = _input.LT(-1);
			setState(392);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,44,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					{
					_localctx = new PrimaryExpressionContext(_parentctx, _parentState);
					pushNewRecursionContext(_localctx, _startState, RULE_primaryExpression);
					setState(388);
					if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
					setState(389);
					recordLiteral();
					}
					} 
				}
				setState(394);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,44,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			unrollRecursionContexts(_parentctx);
		}
		return _localctx;
	}

	public static class LambdaExpressionContext extends ParserRuleContext {
		public RecordTypeContext recordType() {
			return getRuleContext(RecordTypeContext.class,0);
		}
		public TerminalNode ARROW() { return getToken(FunkParser.ARROW, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public LambdaExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_lambdaExpression; }
	}

	public final LambdaExpressionContext lambdaExpression() throws RecognitionException {
		LambdaExpressionContext _localctx = new LambdaExpressionContext(_ctx, getState());
		enterRule(_localctx, 72, RULE_lambdaExpression);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(395);
			recordType();
			setState(396);
			match(ARROW);
			setState(397);
			expression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class BlockExpressionContext extends ParserRuleContext {
		public TerminalNode LBRACE() { return getToken(FunkParser.LBRACE, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode RBRACE() { return getToken(FunkParser.RBRACE, 0); }
		public List<StatementContext> statement() {
			return getRuleContexts(StatementContext.class);
		}
		public StatementContext statement(int i) {
			return getRuleContext(StatementContext.class,i);
		}
		public List<TerminalNode> NEWLINE() { return getTokens(FunkParser.NEWLINE); }
		public TerminalNode NEWLINE(int i) {
			return getToken(FunkParser.NEWLINE, i);
		}
		public BlockExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_blockExpression; }
	}

	public final BlockExpressionContext blockExpression() throws RecognitionException {
		BlockExpressionContext _localctx = new BlockExpressionContext(_ctx, getState());
		enterRule(_localctx, 74, RULE_blockExpression);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(399);
			match(LBRACE);
			setState(404);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,46,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					setState(402);
					_errHandler.sync(this);
					switch (_input.LA(1)) {
					case LET:
					case TYPE:
					case IF:
					case MATCH:
					case HANDLE:
					case RESUME:
					case EFFECT:
					case ABILITY:
					case MODULE:
					case EXPOSE:
					case ACCESS:
					case DO:
					case PLACEHOLDER:
					case LPAREN:
					case LBRACE:
					case Integer:
					case Float:
					case String:
					case Boolean:
					case Identifier:
						{
						setState(400);
						statement();
						}
						break;
					case NEWLINE:
						{
						setState(401);
						match(NEWLINE);
						}
						break;
					default:
						throw new NoViableAltException(this);
					}
					} 
				}
				setState(406);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,46,_ctx);
			}
			setState(407);
			expression();
			setState(408);
			match(RBRACE);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class DoExpressionContext extends ParserRuleContext {
		public TerminalNode DO() { return getToken(FunkParser.DO, 0); }
		public List<TerminalNode> NEWLINE() { return getTokens(FunkParser.NEWLINE); }
		public TerminalNode NEWLINE(int i) {
			return getToken(FunkParser.NEWLINE, i);
		}
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public List<StatementContext> statement() {
			return getRuleContexts(StatementContext.class);
		}
		public StatementContext statement(int i) {
			return getRuleContext(StatementContext.class,i);
		}
		public DoExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_doExpression; }
	}

	public final DoExpressionContext doExpression() throws RecognitionException {
		DoExpressionContext _localctx = new DoExpressionContext(_ctx, getState());
		enterRule(_localctx, 76, RULE_doExpression);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(410);
			match(DO);
			setState(411);
			match(NEWLINE);
			setState(416);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,48,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					setState(414);
					_errHandler.sync(this);
					switch (_input.LA(1)) {
					case LET:
					case TYPE:
					case IF:
					case MATCH:
					case HANDLE:
					case RESUME:
					case EFFECT:
					case ABILITY:
					case MODULE:
					case EXPOSE:
					case ACCESS:
					case DO:
					case PLACEHOLDER:
					case LPAREN:
					case LBRACE:
					case Integer:
					case Float:
					case String:
					case Boolean:
					case Identifier:
						{
						setState(412);
						statement();
						}
						break;
					case NEWLINE:
						{
						setState(413);
						match(NEWLINE);
						}
						break;
					default:
						throw new NoViableAltException(this);
					}
					} 
				}
				setState(418);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,48,_ctx);
			}
			setState(419);
			expression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class RecordLiteralContext extends ParserRuleContext {
		public TerminalNode LBRACE() { return getToken(FunkParser.LBRACE, 0); }
		public TerminalNode RBRACE() { return getToken(FunkParser.RBRACE, 0); }
		public List<RecordEntryContext> recordEntry() {
			return getRuleContexts(RecordEntryContext.class);
		}
		public RecordEntryContext recordEntry(int i) {
			return getRuleContext(RecordEntryContext.class,i);
		}
		public List<TerminalNode> COMMA() { return getTokens(FunkParser.COMMA); }
		public TerminalNode COMMA(int i) {
			return getToken(FunkParser.COMMA, i);
		}
		public TerminalNode ELLIPSIS() { return getToken(FunkParser.ELLIPSIS, 0); }
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public RecordLiteralContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_recordLiteral; }
	}

	public final RecordLiteralContext recordLiteral() throws RecognitionException {
		RecordLiteralContext _localctx = new RecordLiteralContext(_ctx, getState());
		enterRule(_localctx, 78, RULE_recordLiteral);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(421);
			match(LBRACE);
			setState(430);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==Identifier) {
				{
				setState(422);
				recordEntry();
				setState(427);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,49,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(423);
						match(COMMA);
						setState(424);
						recordEntry();
						}
						} 
					}
					setState(429);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,49,_ctx);
				}
				}
			}

			setState(435);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==COMMA) {
				{
				setState(432);
				match(COMMA);
				setState(433);
				match(ELLIPSIS);
				setState(434);
				match(Identifier);
				}
			}

			setState(437);
			match(RBRACE);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class RecordEntryContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode ASSIGN() { return getToken(FunkParser.ASSIGN, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public RecordEntryContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_recordEntry; }
	}

	public final RecordEntryContext recordEntry() throws RecognitionException {
		RecordEntryContext _localctx = new RecordEntryContext(_ctx, getState());
		enterRule(_localctx, 80, RULE_recordEntry);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(439);
			match(Identifier);
			setState(440);
			match(ASSIGN);
			setState(441);
			expression();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class RecordTypeContext extends ParserRuleContext {
		public TerminalNode LBRACE() { return getToken(FunkParser.LBRACE, 0); }
		public TerminalNode RBRACE() { return getToken(FunkParser.RBRACE, 0); }
		public List<RecordTypeEntryContext> recordTypeEntry() {
			return getRuleContexts(RecordTypeEntryContext.class);
		}
		public RecordTypeEntryContext recordTypeEntry(int i) {
			return getRuleContext(RecordTypeEntryContext.class,i);
		}
		public List<TerminalNode> COMMA() { return getTokens(FunkParser.COMMA); }
		public TerminalNode COMMA(int i) {
			return getToken(FunkParser.COMMA, i);
		}
		public TerminalNode ELLIPSIS() { return getToken(FunkParser.ELLIPSIS, 0); }
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public RecordTypeContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_recordType; }
	}

	public final RecordTypeContext recordType() throws RecognitionException {
		RecordTypeContext _localctx = new RecordTypeContext(_ctx, getState());
		enterRule(_localctx, 82, RULE_recordType);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(443);
			match(LBRACE);
			setState(452);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==Identifier) {
				{
				setState(444);
				recordTypeEntry();
				setState(449);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,52,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(445);
						match(COMMA);
						setState(446);
						recordTypeEntry();
						}
						} 
					}
					setState(451);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,52,_ctx);
				}
				}
			}

			setState(457);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==COMMA) {
				{
				setState(454);
				match(COMMA);
				setState(455);
				match(ELLIPSIS);
				setState(456);
				match(Identifier);
				}
			}

			setState(459);
			match(RBRACE);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class RecordTypeEntryContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public TerminalNode COLON() { return getToken(FunkParser.COLON, 0); }
		public TypeAnnotationContext typeAnnotation() {
			return getRuleContext(TypeAnnotationContext.class,0);
		}
		public RecordTypeEntryContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_recordTypeEntry; }
	}

	public final RecordTypeEntryContext recordTypeEntry() throws RecognitionException {
		RecordTypeEntryContext _localctx = new RecordTypeEntryContext(_ctx, getState());
		enterRule(_localctx, 84, RULE_recordTypeEntry);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(461);
			match(Identifier);
			setState(462);
			match(COLON);
			setState(463);
			typeAnnotation(0);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class TypeAnnotationContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(FunkParser.Identifier, 0); }
		public RecordTypeContext recordType() {
			return getRuleContext(RecordTypeContext.class,0);
		}
		public VariantTypeContext variantType() {
			return getRuleContext(VariantTypeContext.class,0);
		}
		public FunctionTypeContext functionType() {
			return getRuleContext(FunctionTypeContext.class,0);
		}
		public TypeAnnotationContext typeAnnotation() {
			return getRuleContext(TypeAnnotationContext.class,0);
		}
		public TerminalNode LBRACKET() { return getToken(FunkParser.LBRACKET, 0); }
		public EffectListContext effectList() {
			return getRuleContext(EffectListContext.class,0);
		}
		public TerminalNode RBRACKET() { return getToken(FunkParser.RBRACKET, 0); }
		public TypeAnnotationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_typeAnnotation; }
	}

	public final TypeAnnotationContext typeAnnotation() throws RecognitionException {
		return typeAnnotation(0);
	}

	private TypeAnnotationContext typeAnnotation(int _p) throws RecognitionException {
		ParserRuleContext _parentctx = _ctx;
		int _parentState = getState();
		TypeAnnotationContext _localctx = new TypeAnnotationContext(_ctx, _parentState);
		TypeAnnotationContext _prevctx = _localctx;
		int _startState = 86;
		enterRecursionRule(_localctx, 86, RULE_typeAnnotation, _p);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(470);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,55,_ctx) ) {
			case 1:
				{
				setState(466);
				match(Identifier);
				}
				break;
			case 2:
				{
				setState(467);
				recordType();
				}
				break;
			case 3:
				{
				setState(468);
				variantType();
				}
				break;
			case 4:
				{
				setState(469);
				functionType();
				}
				break;
			}
			_ctx.stop = _input.LT(-1);
			setState(479);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,56,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					{
					_localctx = new TypeAnnotationContext(_parentctx, _parentState);
					pushNewRecursionContext(_localctx, _startState, RULE_typeAnnotation);
					setState(472);
					if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
					setState(473);
					match(LBRACKET);
					setState(474);
					effectList();
					setState(475);
					match(RBRACKET);
					}
					} 
				}
				setState(481);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,56,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			unrollRecursionContexts(_parentctx);
		}
		return _localctx;
	}

	public static class FunctionTypeContext extends ParserRuleContext {
		public RecordTypeContext recordType() {
			return getRuleContext(RecordTypeContext.class,0);
		}
		public TerminalNode ARROW() { return getToken(FunkParser.ARROW, 0); }
		public VariantTypeContext variantType() {
			return getRuleContext(VariantTypeContext.class,0);
		}
		public TerminalNode LBRACKET() { return getToken(FunkParser.LBRACKET, 0); }
		public EffectListContext effectList() {
			return getRuleContext(EffectListContext.class,0);
		}
		public TerminalNode RBRACKET() { return getToken(FunkParser.RBRACKET, 0); }
		public FunctionTypeContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_functionType; }
	}

	public final FunctionTypeContext functionType() throws RecognitionException {
		FunctionTypeContext _localctx = new FunctionTypeContext(_ctx, getState());
		enterRule(_localctx, 88, RULE_functionType);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(482);
			recordType();
			setState(483);
			match(ARROW);
			setState(484);
			variantType();
			setState(485);
			match(LBRACKET);
			setState(486);
			effectList();
			setState(487);
			match(RBRACKET);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class EffectListContext extends ParserRuleContext {
		public List<TerminalNode> Identifier() { return getTokens(FunkParser.Identifier); }
		public TerminalNode Identifier(int i) {
			return getToken(FunkParser.Identifier, i);
		}
		public List<TerminalNode> COMMA() { return getTokens(FunkParser.COMMA); }
		public TerminalNode COMMA(int i) {
			return getToken(FunkParser.COMMA, i);
		}
		public EffectListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_effectList; }
	}

	public final EffectListContext effectList() throws RecognitionException {
		EffectListContext _localctx = new EffectListContext(_ctx, getState());
		enterRule(_localctx, 90, RULE_effectList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(497);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==Identifier) {
				{
				setState(489);
				match(Identifier);
				setState(494);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==COMMA) {
					{
					{
					setState(490);
					match(COMMA);
					setState(491);
					match(Identifier);
					}
					}
					setState(496);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class LiteralContext extends ParserRuleContext {
		public TerminalNode Integer() { return getToken(FunkParser.Integer, 0); }
		public TerminalNode Float() { return getToken(FunkParser.Float, 0); }
		public TerminalNode String() { return getToken(FunkParser.String, 0); }
		public TerminalNode Boolean() { return getToken(FunkParser.Boolean, 0); }
		public LiteralContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_literal; }
	}

	public final LiteralContext literal() throws RecognitionException {
		LiteralContext _localctx = new LiteralContext(_ctx, getState());
		enterRule(_localctx, 92, RULE_literal);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(499);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << Integer) | (1L << Float) | (1L << String) | (1L << Boolean))) != 0)) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public boolean sempred(RuleContext _localctx, int ruleIndex, int predIndex) {
		switch (ruleIndex) {
		case 35:
			return primaryExpression_sempred((PrimaryExpressionContext)_localctx, predIndex);
		case 43:
			return typeAnnotation_sempred((TypeAnnotationContext)_localctx, predIndex);
		}
		return true;
	}
	private boolean primaryExpression_sempred(PrimaryExpressionContext _localctx, int predIndex) {
		switch (predIndex) {
		case 0:
			return precpred(_ctx, 1);
		}
		return true;
	}
	private boolean typeAnnotation_sempred(TypeAnnotationContext _localctx, int predIndex) {
		switch (predIndex) {
		case 1:
			return precpred(_ctx, 1);
		}
		return true;
	}

	public static final String _serializedATN =
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\3\60\u01f8\4\2\t\2"+
		"\4\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4\13"+
		"\t\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22\t\22"+
		"\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\4\31\t\31"+
		"\4\32\t\32\4\33\t\33\4\34\t\34\4\35\t\35\4\36\t\36\4\37\t\37\4 \t \4!"+
		"\t!\4\"\t\"\4#\t#\4$\t$\4%\t%\4&\t&\4\'\t\'\4(\t(\4)\t)\4*\t*\4+\t+\4"+
		",\t,\4-\t-\4.\t.\4/\t/\4\60\t\60\3\2\3\2\7\2c\n\2\f\2\16\2f\13\2\3\2\3"+
		"\2\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\5\3s\n\3\3\4\3\4\3\4\3\4\3\4\3"+
		"\5\3\5\3\5\5\5}\n\5\3\6\3\6\3\6\7\6\u0082\n\6\f\6\16\6\u0085\13\6\3\7"+
		"\5\7\u0088\n\7\3\7\3\7\3\b\3\b\3\b\3\b\3\b\3\b\3\b\5\b\u0093\n\b\3\t\3"+
		"\t\3\t\7\t\u0098\n\t\f\t\16\t\u009b\13\t\3\n\3\n\3\n\5\n\u00a0\n\n\3\13"+
		"\3\13\3\13\3\13\5\13\u00a6\n\13\3\13\3\13\3\13\5\13\u00ab\n\13\3\f\3\f"+
		"\3\f\5\f\u00b0\n\f\3\f\3\f\3\f\5\f\u00b5\n\f\3\r\6\r\u00b8\n\r\r\r\16"+
		"\r\u00b9\3\16\3\16\5\16\u00be\n\16\3\17\3\17\3\17\3\17\7\17\u00c4\n\17"+
		"\f\17\16\17\u00c7\13\17\3\17\3\17\5\17\u00cb\n\17\3\17\3\17\3\20\3\20"+
		"\5\20\u00d1\n\20\3\21\3\21\3\21\7\21\u00d6\n\21\f\21\16\21\u00d9\13\21"+
		"\3\22\3\22\3\22\3\22\3\22\3\22\3\22\5\22\u00e2\n\22\3\23\3\23\3\23\3\23"+
		"\3\23\7\23\u00e9\n\23\f\23\16\23\u00ec\13\23\3\24\3\24\3\24\3\24\3\24"+
		"\3\24\5\24\u00f4\n\24\3\25\3\25\3\25\5\25\u00f9\n\25\3\26\3\26\5\26\u00fd"+
		"\n\26\3\27\3\27\3\30\3\30\3\30\7\30\u0104\n\30\f\30\16\30\u0107\13\30"+
		"\3\31\3\31\3\31\3\31\7\31\u010d\n\31\f\31\16\31\u0110\13\31\3\31\3\31"+
		"\3\31\5\31\u0115\n\31\3\32\3\32\3\32\3\32\5\32\u011b\n\32\3\33\3\33\3"+
		"\33\3\33\5\33\u0121\n\33\3\33\5\33\u0124\n\33\3\34\3\34\3\34\3\34\7\34"+
		"\u012a\n\34\f\34\16\34\u012d\13\34\5\34\u012f\n\34\3\34\3\34\3\35\3\35"+
		"\3\35\5\35\u0136\n\35\3\36\3\36\3\36\3\36\7\36\u013c\n\36\f\36\16\36\u013f"+
		"\13\36\3\36\3\36\3\36\5\36\u0144\n\36\3\36\5\36\u0147\n\36\3\37\3\37\5"+
		"\37\u014b\n\37\3\37\3\37\3\37\5\37\u0150\n\37\3 \3 \3 \3 \3 \3 \3 \3 "+
		"\5 \u015a\n \3!\3!\3!\5!\u015f\n!\3\"\3\"\3\"\7\"\u0164\n\"\f\"\16\"\u0167"+
		"\13\"\3#\3#\3#\7#\u016c\n#\f#\16#\u016f\13#\3$\3$\3$\7$\u0174\n$\f$\16"+
		"$\u0177\13$\3%\3%\3%\3%\3%\3%\3%\3%\3%\3%\3%\3%\5%\u0185\n%\3%\3%\7%\u0189"+
		"\n%\f%\16%\u018c\13%\3&\3&\3&\3&\3\'\3\'\3\'\7\'\u0195\n\'\f\'\16\'\u0198"+
		"\13\'\3\'\3\'\3\'\3(\3(\3(\3(\7(\u01a1\n(\f(\16(\u01a4\13(\3(\3(\3)\3"+
		")\3)\3)\7)\u01ac\n)\f)\16)\u01af\13)\5)\u01b1\n)\3)\3)\3)\5)\u01b6\n)"+
		"\3)\3)\3*\3*\3*\3*\3+\3+\3+\3+\7+\u01c2\n+\f+\16+\u01c5\13+\5+\u01c7\n"+
		"+\3+\3+\3+\5+\u01cc\n+\3+\3+\3,\3,\3,\3,\3-\3-\3-\3-\3-\5-\u01d9\n-\3"+
		"-\3-\3-\3-\3-\7-\u01e0\n-\f-\16-\u01e3\13-\3.\3.\3.\3.\3.\3.\3.\3/\3/"+
		"\3/\7/\u01ef\n/\f/\16/\u01f2\13/\5/\u01f4\n/\3\60\3\60\3\60\2\4HX\61\2"+
		"\4\6\b\n\f\16\20\22\24\26\30\32\34\36 \"$&(*,.\60\62\64\668:<>@BDFHJL"+
		"NPRTVXZ\\^\2\6\4\2\4\4\f\r\4\2\24\25\30\30\3\2\26\27\3\2),\2\u0214\2d"+
		"\3\2\2\2\4r\3\2\2\2\6t\3\2\2\2\by\3\2\2\2\n~\3\2\2\2\f\u0087\3\2\2\2\16"+
		"\u008b\3\2\2\2\20\u0094\3\2\2\2\22\u009c\3\2\2\2\24\u00a1\3\2\2\2\26\u00ac"+
		"\3\2\2\2\30\u00b7\3\2\2\2\32\u00bd\3\2\2\2\34\u00bf\3\2\2\2\36\u00ce\3"+
		"\2\2\2 \u00d2\3\2\2\2\"\u00da\3\2\2\2$\u00e3\3\2\2\2&\u00ed\3\2\2\2(\u00f5"+
		"\3\2\2\2*\u00fa\3\2\2\2,\u00fe\3\2\2\2.\u0100\3\2\2\2\60\u0114\3\2\2\2"+
		"\62\u0116\3\2\2\2\64\u0123\3\2\2\2\66\u0125\3\2\2\28\u0132\3\2\2\2:\u0146"+
		"\3\2\2\2<\u0148\3\2\2\2>\u0159\3\2\2\2@\u015b\3\2\2\2B\u0160\3\2\2\2D"+
		"\u0168\3\2\2\2F\u0170\3\2\2\2H\u0184\3\2\2\2J\u018d\3\2\2\2L\u0191\3\2"+
		"\2\2N\u019c\3\2\2\2P\u01a7\3\2\2\2R\u01b9\3\2\2\2T\u01bd\3\2\2\2V\u01cf"+
		"\3\2\2\2X\u01d8\3\2\2\2Z\u01e4\3\2\2\2\\\u01f3\3\2\2\2^\u01f5\3\2\2\2"+
		"`c\5\4\3\2ac\7\60\2\2b`\3\2\2\2ba\3\2\2\2cf\3\2\2\2db\3\2\2\2de\3\2\2"+
		"\2eg\3\2\2\2fd\3\2\2\2gh\7\2\2\3h\3\3\2\2\2is\5\24\13\2js\5\26\f\2ks\5"+
		"\"\22\2ls\5$\23\2ms\5\6\4\2ns\5\b\5\2os\5\16\b\2ps\5(\25\2qs\5*\26\2r"+
		"i\3\2\2\2rj\3\2\2\2rk\3\2\2\2rl\3\2\2\2rm\3\2\2\2rn\3\2\2\2ro\3\2\2\2"+
		"rp\3\2\2\2rq\3\2\2\2s\5\3\2\2\2tu\7\17\2\2uv\7-\2\2vw\7\16\2\2wx\7\60"+
		"\2\2x\7\3\2\2\2yz\7\20\2\2z|\5\n\6\2{}\7\60\2\2|{\3\2\2\2|}\3\2\2\2}\t"+
		"\3\2\2\2~\u0083\5\f\7\2\177\u0080\7\'\2\2\u0080\u0082\5\f\7\2\u0081\177"+
		"\3\2\2\2\u0082\u0085\3\2\2\2\u0083\u0081\3\2\2\2\u0083\u0084\3\2\2\2\u0084"+
		"\13\3\2\2\2\u0085\u0083\3\2\2\2\u0086\u0088\t\2\2\2\u0087\u0086\3\2\2"+
		"\2\u0087\u0088\3\2\2\2\u0088\u0089\3\2\2\2\u0089\u008a\7-\2\2\u008a\r"+
		"\3\2\2\2\u008b\u008c\7\21\2\2\u008c\u008d\7-\2\2\u008d\u008e\7(\2\2\u008e"+
		"\u008f\7!\2\2\u008f\u0090\5\20\t\2\u0090\u0092\7\"\2\2\u0091\u0093\7\60"+
		"\2\2\u0092\u0091\3\2\2\2\u0092\u0093\3\2\2\2\u0093\17\3\2\2\2\u0094\u0099"+
		"\5\22\n\2\u0095\u0096\7\'\2\2\u0096\u0098\5\22\n\2\u0097\u0095\3\2\2\2"+
		"\u0098\u009b\3\2\2\2\u0099\u0097\3\2\2\2\u0099\u009a\3\2\2\2\u009a\21"+
		"\3\2\2\2\u009b\u0099\3\2\2\2\u009c\u009f\7-\2\2\u009d\u009e\7\22\2\2\u009e"+
		"\u00a0\7-\2\2\u009f\u009d\3\2\2\2\u009f\u00a0\3\2\2\2\u00a0\23\3\2\2\2"+
		"\u00a1\u00a2\7\3\2\2\u00a2\u00a5\7-\2\2\u00a3\u00a4\7&\2\2\u00a4\u00a6"+
		"\5X-\2\u00a5\u00a3\3\2\2\2\u00a5\u00a6\3\2\2\2\u00a6\u00a7\3\2\2\2\u00a7"+
		"\u00a8\7\31\2\2\u00a8\u00aa\5,\27\2\u00a9\u00ab\7\60\2\2\u00aa\u00a9\3"+
		"\2\2\2\u00aa\u00ab\3\2\2\2\u00ab\25\3\2\2\2\u00ac\u00ad\7\4\2\2\u00ad"+
		"\u00af\7-\2\2\u00ae\u00b0\5\30\r\2\u00af\u00ae\3\2\2\2\u00af\u00b0\3\2"+
		"\2\2\u00b0\u00b1\3\2\2\2\u00b1\u00b2\7\31\2\2\u00b2\u00b4\5\32\16\2\u00b3"+
		"\u00b5\7\60\2\2\u00b4\u00b3\3\2\2\2\u00b4\u00b5\3\2\2\2\u00b5\27\3\2\2"+
		"\2\u00b6\u00b8\7-\2\2\u00b7\u00b6\3\2\2\2\u00b8\u00b9\3\2\2\2\u00b9\u00b7"+
		"\3\2\2\2\u00b9\u00ba\3\2\2\2\u00ba\31\3\2\2\2\u00bb\u00be\5T+\2\u00bc"+
		"\u00be\5\34\17\2\u00bd\u00bb\3\2\2\2\u00bd\u00bc\3\2\2\2\u00be\33\3\2"+
		"\2\2\u00bf\u00c0\7%\2\2\u00c0\u00c5\5\36\20\2\u00c1\u00c2\7\'\2\2\u00c2"+
		"\u00c4\5\36\20\2\u00c3\u00c1\3\2\2\2\u00c4\u00c7\3\2\2\2\u00c5\u00c3\3"+
		"\2\2\2\u00c5\u00c6\3\2\2\2\u00c6\u00ca\3\2\2\2\u00c7\u00c5\3\2\2\2\u00c8"+
		"\u00c9\7\'\2\2\u00c9\u00cb\7\36\2\2\u00ca\u00c8\3\2\2\2\u00ca\u00cb\3"+
		"\2\2\2\u00cb\u00cc\3\2\2\2\u00cc\u00cd\7%\2\2\u00cd\35\3\2\2\2\u00ce\u00d0"+
		"\5 \21\2\u00cf\u00d1\5T+\2\u00d0\u00cf\3\2\2\2\u00d0\u00d1\3\2\2\2\u00d1"+
		"\37\3\2\2\2\u00d2\u00d7\7-\2\2\u00d3\u00d4\7(\2\2\u00d4\u00d6\7-\2\2\u00d5"+
		"\u00d3\3\2\2\2\u00d6\u00d9\3\2\2\2\u00d7\u00d5\3\2\2\2\u00d7\u00d8\3\2"+
		"\2\2\u00d8!\3\2\2\2\u00d9\u00d7\3\2\2\2\u00da\u00db\7\f\2\2\u00db\u00dc"+
		"\7-\2\2\u00dc\u00dd\7&\2\2\u00dd\u00de\5T+\2\u00de\u00df\7\33\2\2\u00df"+
		"\u00e1\5\34\17\2\u00e0\u00e2\7\60\2\2\u00e1\u00e0\3\2\2\2\u00e1\u00e2"+
		"\3\2\2\2\u00e2#\3\2\2\2\u00e3\u00e4\7\r\2\2\u00e4\u00e5\7-\2\2\u00e5\u00e6"+
		"\7\16\2\2\u00e6\u00ea\7\60\2\2\u00e7\u00e9\5&\24\2\u00e8\u00e7\3\2\2\2"+
		"\u00e9\u00ec\3\2\2\2\u00ea\u00e8\3\2\2\2\u00ea\u00eb\3\2\2\2\u00eb%\3"+
		"\2\2\2\u00ec\u00ea\3\2\2\2\u00ed\u00ee\7-\2\2\u00ee\u00ef\7&\2\2\u00ef"+
		"\u00f0\5T+\2\u00f0\u00f1\7\33\2\2\u00f1\u00f3\5\34\17\2\u00f2\u00f4\7"+
		"\60\2\2\u00f3\u00f2\3\2\2\2\u00f3\u00f4\3\2\2\2\u00f4\'\3\2\2\2\u00f5"+
		"\u00f6\7\13\2\2\u00f6\u00f8\5,\27\2\u00f7\u00f9\7\60\2\2\u00f8\u00f7\3"+
		"\2\2\2\u00f8\u00f9\3\2\2\2\u00f9)\3\2\2\2\u00fa\u00fc\5,\27\2\u00fb\u00fd"+
		"\7\60\2\2\u00fc\u00fb\3\2\2\2\u00fc\u00fd\3\2\2\2\u00fd+\3\2\2\2\u00fe"+
		"\u00ff\5.\30\2\u00ff-\3\2\2\2\u0100\u0105\5\60\31\2\u0101\u0102\7\32\2"+
		"\2\u0102\u0104\5\60\31\2\u0103\u0101\3\2\2\2\u0104\u0107\3\2\2\2\u0105"+
		"\u0103\3\2\2\2\u0105\u0106\3\2\2\2\u0106/\3\2\2\2\u0107\u0105\3\2\2\2"+
		"\u0108\u0109\7\t\2\2\u0109\u010a\5,\27\2\u010a\u010e\7!\2\2\u010b\u010d"+
		"\5\62\32\2\u010c\u010b\3\2\2\2\u010d\u0110\3\2\2\2\u010e\u010c\3\2\2\2"+
		"\u010e\u010f\3\2\2\2\u010f\u0111\3\2\2\2\u0110\u010e\3\2\2\2\u0111\u0112"+
		"\7\"\2\2\u0112\u0115\3\2\2\2\u0113\u0115\5:\36\2\u0114\u0108\3\2\2\2\u0114"+
		"\u0113\3\2\2\2\u0115\61\3\2\2\2\u0116\u0117\5\64\33\2\u0117\u0118\7\33"+
		"\2\2\u0118\u011a\5,\27\2\u0119\u011b\7\60\2\2\u011a\u0119\3\2\2\2\u011a"+
		"\u011b\3\2\2\2\u011b\63\3\2\2\2\u011c\u0124\7\35\2\2\u011d\u0124\7-\2"+
		"\2\u011e\u0120\7-\2\2\u011f\u0121\5\66\34\2\u0120\u011f\3\2\2\2\u0120"+
		"\u0121\3\2\2\2\u0121\u0124\3\2\2\2\u0122\u0124\5\66\34\2\u0123\u011c\3"+
		"\2\2\2\u0123\u011d\3\2\2\2\u0123\u011e\3\2\2\2\u0123\u0122\3\2\2\2\u0124"+
		"\65\3\2\2\2\u0125\u012e\7!\2\2\u0126\u012b\58\35\2\u0127\u0128\7\'\2\2"+
		"\u0128\u012a\58\35\2\u0129\u0127\3\2\2\2\u012a\u012d\3\2\2\2\u012b\u0129"+
		"\3\2\2\2\u012b\u012c\3\2\2\2\u012c\u012f\3\2\2\2\u012d\u012b\3\2\2\2\u012e"+
		"\u0126\3\2\2\2\u012e\u012f\3\2\2\2\u012f\u0130\3\2\2\2\u0130\u0131\7\""+
		"\2\2\u0131\67\3\2\2\2\u0132\u0135\7-\2\2\u0133\u0134\7\31\2\2\u0134\u0136"+
		"\5\64\33\2\u0135\u0133\3\2\2\2\u0135\u0136\3\2\2\2\u01369\3\2\2\2\u0137"+
		"\u0138\7\n\2\2\u0138\u0139\5,\27\2\u0139\u013d\7!\2\2\u013a\u013c\5<\37"+
		"\2\u013b\u013a\3\2\2\2\u013c\u013f\3\2\2\2\u013d\u013b\3\2\2\2\u013d\u013e"+
		"\3\2\2\2\u013e\u0140\3\2\2\2\u013f\u013d\3\2\2\2\u0140\u0143\7\"\2\2\u0141"+
		"\u0142\7\5\2\2\u0142\u0144\5P)\2\u0143\u0141\3\2\2\2\u0143\u0144\3\2\2"+
		"\2\u0144\u0147\3\2\2\2\u0145\u0147\5> \2\u0146\u0137\3\2\2\2\u0146\u0145"+
		"\3\2\2\2\u0147;\3\2\2\2\u0148\u014a\7-\2\2\u0149\u014b\5\66\34\2\u014a"+
		"\u0149\3\2\2\2\u014a\u014b\3\2\2\2\u014b\u014c\3\2\2\2\u014c\u014d\7\33"+
		"\2\2\u014d\u014f\5,\27\2\u014e\u0150\7\60\2\2\u014f\u014e\3\2\2\2\u014f"+
		"\u0150\3\2\2\2\u0150=\3\2\2\2\u0151\u0152\7\6\2\2\u0152\u0153\5,\27\2"+
		"\u0153\u0154\7\7\2\2\u0154\u0155\5,\27\2\u0155\u0156\7\b\2\2\u0156\u0157"+
		"\5,\27\2\u0157\u015a\3\2\2\2\u0158\u015a\5@!\2\u0159\u0151\3\2\2\2\u0159"+
		"\u0158\3\2\2\2\u015a?\3\2\2\2\u015b\u015e\5B\"\2\u015c\u015d\7\5\2\2\u015d"+
		"\u015f\5P)\2\u015e\u015c\3\2\2\2\u015e\u015f\3\2\2\2\u015fA\3\2\2\2\u0160"+
		"\u0165\5D#\2\u0161\u0162\t\3\2\2\u0162\u0164\5D#\2\u0163\u0161\3\2\2\2"+
		"\u0164\u0167\3\2\2\2\u0165\u0163\3\2\2\2\u0165\u0166\3\2\2\2\u0166C\3"+
		"\2\2\2\u0167\u0165\3\2\2\2\u0168\u016d\5F$\2\u0169\u016a\t\4\2\2\u016a"+
		"\u016c\5F$\2\u016b\u0169\3\2\2\2\u016c\u016f\3\2\2\2\u016d\u016b\3\2\2"+
		"\2\u016d\u016e\3\2\2\2\u016eE\3\2\2\2\u016f\u016d\3\2\2\2\u0170\u0175"+
		"\5H%\2\u0171\u0172\7(\2\2\u0172\u0174\7-\2\2\u0173\u0171\3\2\2\2\u0174"+
		"\u0177\3\2\2\2\u0175\u0173\3\2\2\2\u0175\u0176\3\2\2\2\u0176G\3\2\2\2"+
		"\u0177\u0175\3\2\2\2\u0178\u0179\b%\1\2\u0179\u0185\5^\60\2\u017a\u0185"+
		"\7-\2\2\u017b\u0185\7\34\2\2\u017c\u017d\7\37\2\2\u017d\u017e\5,\27\2"+
		"\u017e\u017f\7 \2\2\u017f\u0185\3\2\2\2\u0180\u0185\5P)\2\u0181\u0185"+
		"\5L\'\2\u0182\u0185\5N(\2\u0183\u0185\5J&\2\u0184\u0178\3\2\2\2\u0184"+
		"\u017a\3\2\2\2\u0184\u017b\3\2\2\2\u0184\u017c\3\2\2\2\u0184\u0180\3\2"+
		"\2\2\u0184\u0181\3\2\2\2\u0184\u0182\3\2\2\2\u0184\u0183\3\2\2\2\u0185"+
		"\u018a\3\2\2\2\u0186\u0187\f\3\2\2\u0187\u0189\5P)\2\u0188\u0186\3\2\2"+
		"\2\u0189\u018c\3\2\2\2\u018a\u0188\3\2\2\2\u018a\u018b\3\2\2\2\u018bI"+
		"\3\2\2\2\u018c\u018a\3\2\2\2\u018d\u018e\5T+\2\u018e\u018f\7\33\2\2\u018f"+
		"\u0190\5,\27\2\u0190K\3\2\2\2\u0191\u0196\7!\2\2\u0192\u0195\5\4\3\2\u0193"+
		"\u0195\7\60\2\2\u0194\u0192\3\2\2\2\u0194\u0193\3\2\2\2\u0195\u0198\3"+
		"\2\2\2\u0196\u0194\3\2\2\2\u0196\u0197\3\2\2\2\u0197\u0199\3\2\2\2\u0198"+
		"\u0196\3\2\2\2\u0199\u019a\5,\27\2\u019a\u019b\7\"\2\2\u019bM\3\2\2\2"+
		"\u019c\u019d\7\23\2\2\u019d\u01a2\7\60\2\2\u019e\u01a1\5\4\3\2\u019f\u01a1"+
		"\7\60\2\2\u01a0\u019e\3\2\2\2\u01a0\u019f\3\2\2\2\u01a1\u01a4\3\2\2\2"+
		"\u01a2\u01a0\3\2\2\2\u01a2\u01a3\3\2\2\2\u01a3\u01a5\3\2\2\2\u01a4\u01a2"+
		"\3\2\2\2\u01a5\u01a6\5,\27\2\u01a6O\3\2\2\2\u01a7\u01b0\7!\2\2\u01a8\u01ad"+
		"\5R*\2\u01a9\u01aa\7\'\2\2\u01aa\u01ac\5R*\2\u01ab\u01a9\3\2\2\2\u01ac"+
		"\u01af\3\2\2\2\u01ad\u01ab\3\2\2\2\u01ad\u01ae\3\2\2\2\u01ae\u01b1\3\2"+
		"\2\2\u01af\u01ad\3\2\2\2\u01b0\u01a8\3\2\2\2\u01b0\u01b1\3\2\2\2\u01b1"+
		"\u01b5\3\2\2\2\u01b2\u01b3\7\'\2\2\u01b3\u01b4\7\36\2\2\u01b4\u01b6\7"+
		"-\2\2\u01b5\u01b2\3\2\2\2\u01b5\u01b6\3\2\2\2\u01b6\u01b7\3\2\2\2\u01b7"+
		"\u01b8\7\"\2\2\u01b8Q\3\2\2\2\u01b9\u01ba\7-\2\2\u01ba\u01bb\7\31\2\2"+
		"\u01bb\u01bc\5,\27\2\u01bcS\3\2\2\2\u01bd\u01c6\7!\2\2\u01be\u01c3\5V"+
		",\2\u01bf\u01c0\7\'\2\2\u01c0\u01c2\5V,\2\u01c1\u01bf\3\2\2\2\u01c2\u01c5"+
		"\3\2\2\2\u01c3\u01c1\3\2\2\2\u01c3\u01c4\3\2\2\2\u01c4\u01c7\3\2\2\2\u01c5"+
		"\u01c3\3\2\2\2\u01c6\u01be\3\2\2\2\u01c6\u01c7\3\2\2\2\u01c7\u01cb\3\2"+
		"\2\2\u01c8\u01c9\7\'\2\2\u01c9\u01ca\7\36\2\2\u01ca\u01cc\7-\2\2\u01cb"+
		"\u01c8\3\2\2\2\u01cb\u01cc\3\2\2\2\u01cc\u01cd\3\2\2\2\u01cd\u01ce\7\""+
		"\2\2\u01ceU\3\2\2\2\u01cf\u01d0\7-\2\2\u01d0\u01d1\7&\2\2\u01d1\u01d2"+
		"\5X-\2\u01d2W\3\2\2\2\u01d3\u01d4\b-\1\2\u01d4\u01d9\7-\2\2\u01d5\u01d9"+
		"\5T+\2\u01d6\u01d9\5\34\17\2\u01d7\u01d9\5Z.\2\u01d8\u01d3\3\2\2\2\u01d8"+
		"\u01d5\3\2\2\2\u01d8\u01d6\3\2\2\2\u01d8\u01d7\3\2\2\2\u01d9\u01e1\3\2"+
		"\2\2\u01da\u01db\f\3\2\2\u01db\u01dc\7#\2\2\u01dc\u01dd\5\\/\2\u01dd\u01de"+
		"\7$\2\2\u01de\u01e0\3\2\2\2\u01df\u01da\3\2\2\2\u01e0\u01e3\3\2\2\2\u01e1"+
		"\u01df\3\2\2\2\u01e1\u01e2\3\2\2\2\u01e2Y\3\2\2\2\u01e3\u01e1\3\2\2\2"+
		"\u01e4\u01e5\5T+\2\u01e5\u01e6\7\33\2\2\u01e6\u01e7\5\34\17\2\u01e7\u01e8"+
		"\7#\2\2\u01e8\u01e9\5\\/\2\u01e9\u01ea\7$\2\2\u01ea[\3\2\2\2\u01eb\u01f0"+
		"\7-\2\2\u01ec\u01ed\7\'\2\2\u01ed\u01ef\7-\2\2\u01ee\u01ec\3\2\2\2\u01ef"+
		"\u01f2\3\2\2\2\u01f0\u01ee\3\2\2\2\u01f0\u01f1\3\2\2\2\u01f1\u01f4\3\2"+
		"\2\2\u01f2\u01f0\3\2\2\2\u01f3\u01eb\3\2\2\2\u01f3\u01f4\3\2\2\2\u01f4"+
		"]\3\2\2\2\u01f5\u01f6\t\5\2\2\u01f6_\3\2\2\2=bdr|\u0083\u0087\u0092\u0099"+
		"\u009f\u00a5\u00aa\u00af\u00b4\u00b9\u00bd\u00c5\u00ca\u00d0\u00d7\u00e1"+
		"\u00ea\u00f3\u00f8\u00fc\u0105\u010e\u0114\u011a\u0120\u0123\u012b\u012e"+
		"\u0135\u013d\u0143\u0146\u014a\u014f\u0159\u015e\u0165\u016d\u0175\u0184"+
		"\u018a\u0194\u0196\u01a0\u01a2\u01ad\u01b0\u01b5\u01c3\u01c6\u01cb\u01d8"+
		"\u01e1\u01f0\u01f3";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}