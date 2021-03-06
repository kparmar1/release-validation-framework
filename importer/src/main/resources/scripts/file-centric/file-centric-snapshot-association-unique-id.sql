
/******************************************************************************** 
	file-centric-snapshot-association-unique-id

	Assertion:
	ID is unique in the ASSOCIATION REFSET snapshot.

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select 
		<RUNID>,
		'<ASSERTIONUUID>',
		a.referencedcomponentid,
		concat('ASSOC RS: id=',a.id, ':Non unique id in current release file.') 	
	from curr_associationrefset_s a
	group by a.id
	having  count(a.id) > 1;
	commit;
